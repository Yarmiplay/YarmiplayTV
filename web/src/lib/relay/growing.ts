import type { GrowingByteSource } from "../player/bytes";
import { HttpError } from "../player/bytes";
import { parseSegmentPage, type GrowingMedia, type Segment } from "../syncplay/yarmiplay";

/**
 * A growing relay file's segments as far as the server has indexed them, with when each starts in seconds from the
 * start of the file. Segment 0 starts at byte 0; each later one starts on a keyframe.
 */
export class SegmentIndex {
  private readonly segments: Segment[] = [];
  private readonly starts: number[] = [];
  private total = 0;

  get count(): number {
    return this.segments.length;
  }

  /** Seconds the indexed segments last. */
  get seconds(): number {
    return this.total;
  }

  /** Adds segments [from], [from] + 1…, skipping the ones already known; a page that leaves a gap is ignored. */
  append(from: number, segments: readonly Segment[]): boolean {
    if (from > this.count) return false;
    let added = false;
    for (let i = this.count - from; i < segments.length; i++) {
      const s = segments[i];
      this.starts.push(this.total);
      this.segments.push(s);
      this.total += s.duration;
      added = true;
    }
    return added;
  }

  /** The start of the segment holding [seconds] and when it starts: the first before 0, the last past the end. */
  seekPoint(seconds: number): { offset: number; time: number } {
    if (this.count === 0 || !(seconds > 0)) return { offset: 0, time: 0 };
    let lo = 0;
    let hi = this.count - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (this.starts[mid] <= seconds) lo = mid;
      else hi = mid - 1;
    }
    return { offset: this.segments[lo].offset, time: this.starts[lo] };
  }

  /** [seconds] while it is inside the index; past it, the start of the last segment (0 with none yet). */
  clamp(seconds: number): number {
    if (!(seconds > 0)) return 0;
    if (seconds < this.total) return seconds;
    return this.count > 0 ? this.starts[this.count - 1] : 0;
  }
}

/** Bytes of a block, the unit kept in memory. */
const BLOCK = 1 << 20;
/** At most this many blocks in one request, as the relay asks. */
const MAX_BLOCKS_PER_REQUEST = 32;
/** Blocks kept in memory, least recently used dropped first. */
const CACHE_BLOCKS = 96;
/** Complete blocks fetched ahead of the last one read. */
const READ_AHEAD_BLOCKS = 8;
/**
 * A read this far past the written end is a look at the end of the file (a demuxer probing for the length), not
 * playback catching up: it gets the end at once instead of waiting for bytes that are hours away.
 */
export const FAR_BYTES = 64 * BLOCK;
const POLL_MS = 1_000;

export interface GrowingSourceOptions {
  /** The file's bytes, read with Range requests. */
  url: string;
  /** Its segment index (`…/segments`); `?from=K` is added. */
  segmentsUrl: string;
  headers: Record<string, string>;
  name: string;
  /** The relay entry's size: an upper bound, given to the demuxer as the file's size. */
  size: number;
  media: GrowingMedia;
  /** The relay entry's duration: the whole length for a file that will end, 0 when unknown. */
  duration: number;
  fetch?: typeof fetch;
  pollMs?: number;
}

/**
 * A relay file the server is still writing, read with Range requests that never go past the written length. Reads at
 * the end wait while the server keeps writing; the segment index (polled while it grows) gives the player its length
 * and where to read from for a time.
 */
export class GrowingSource implements GrowingByteSource {
  readonly growingSource = true as const;
  readonly name: string;
  readonly size: number;
  private readonly index = new SegmentIndex();
  private length: number;
  private growing: boolean;
  private live: boolean;
  private readonly blocks = new Map<number, Uint8Array>();
  private readonly inflight = new Map<number, Promise<void>>();
  private readonly listeners = new Set<() => void>();
  private readonly wakers = new Set<() => void>();
  private readonly controller = new AbortController();
  private refreshing: Promise<void> | null = null;
  private pollTimer: ReturnType<typeof setTimeout> | undefined;
  private closed = false;
  private readonly fetchFn: typeof fetch;
  private readonly pollMs: number;

  private constructor(private readonly opts: GrowingSourceOptions) {
    this.name = opts.name;
    this.length = opts.media.length;
    this.growing = opts.media.growing;
    this.live = opts.media.live;
    this.size = Math.max(opts.size, opts.media.length, 1);
    this.fetchFn = opts.fetch ?? ((input, init) => fetch(input, init));
    this.pollMs = opts.pollMs ?? POLL_MS;
  }

  /** Opens the file with its segment index as the server has it now, and keeps the index current while it grows. */
  static async open(opts: GrowingSourceOptions): Promise<GrowingSource> {
    const source = new GrowingSource(opts);
    await source.refresh();
    source.schedulePoll();
    return source;
  }

  get ended(): boolean {
    return !this.growing;
  }

  /** Bytes written so far. */
  get written(): number {
    return this.length;
  }

  /** Seconds the index covers. */
  get indexedSeconds(): number {
    return this.index.seconds;
  }

  playableDuration(): number {
    if (!this.growing || this.live) return this.index.seconds;
    return this.opts.duration > 0 ? Math.max(this.opts.duration, this.index.seconds) : this.index.seconds;
  }

  clampSeconds(seconds: number): number {
    return this.index.clamp(seconds);
  }

  seekPoint(seconds: number): { offset: number; time: number } {
    return this.index.seekPoint(seconds);
  }

  onChange(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async read(position: number, want: number): Promise<Uint8Array> {
    if (this.closed) throw new Error("The file was closed");
    if (position >= this.length && !(await this.waitFor(position))) return new Uint8Array(0);
    const end = Math.min(position + Math.max(1, want), this.length);
    if (position >= end) return new Uint8Array(0);
    const first = Math.floor(position / BLOCK);
    const last = Math.floor((end - 1) / BLOCK);
    // Blocks wholly written are fetched whole and kept; the block being written is read up to the written end only.
    const fullBlocks = Math.floor(this.length / BLOCK);
    const lastFull = Math.min(last, fullBlocks - 1);
    const out = new Uint8Array(end - position);
    let filled = 0;
    if (first <= lastFull) {
      const blocks = await this.blocksFor(first, lastFull);
      this.prefetch(lastFull + 1, Math.min(lastFull + READ_AHEAD_BLOCKS, fullBlocks - 1));
      for (let b = first; b <= lastFull; b++) {
        const block = blocks[b - first];
        const blockStart = b * BLOCK;
        const from = Math.max(position, blockStart) - blockStart;
        const to = Math.min(end, blockStart + block.length) - blockStart;
        out.set(block.subarray(from, to), blockStart + from - position);
        filled = blockStart + to - position;
      }
    }
    if (last > lastFull) {
      const from = Math.max(position, (lastFull + 1) * BLOCK);
      const tail = await this.fetchBytes(from, end);
      out.set(tail, from - position);
      filled = from - position + tail.length;
    }
    return filled < out.length ? out.subarray(0, filled) : out;
  }

  /** Stops waiting and polling; reads waiting for bytes fail. The demuxer closes right after. */
  abort() {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.pollTimer);
    this.controller.abort();
    for (const wake of this.wakers) wake();
    this.wakers.clear();
  }

  close() {
    this.abort();
    this.blocks.clear();
    this.listeners.clear();
  }

  /** Asks the server for the segments after the ones known, and the length and state with them. */
  refresh(): Promise<void> {
    if (this.closed) return Promise.resolve();
    this.refreshing ??= this.fetchSegments().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  private async fetchSegments() {
    let response: Response;
    try {
      response = await this.fetchFn(`${this.opts.segmentsUrl}?from=${this.index.count}`, {
        headers: this.opts.headers,
        signal: this.controller.signal,
      });
    } catch {
      return; // The network: asked again on the next poll.
    }
    if (this.closed) return;
    if (response.status === 404) {
      // The server no longer has it: what was written is all there will be.
      await response.body?.cancel().catch(() => {});
      if (this.growing) {
        this.growing = false;
        this.notify();
      }
      return;
    }
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      return;
    }
    const page = parseSegmentPage(await response.json().catch(() => null));
    if (!page || this.closed) return;
    let changed = this.index.append(page.from, page.segments);
    if (page.length > this.length) {
      this.length = page.length;
      changed = true;
    }
    if (page.growing !== this.growing || page.live !== this.live) {
      this.growing = page.growing;
      this.live = page.live;
      changed = true;
    }
    if (changed) this.notify();
  }

  private schedulePoll() {
    if (this.closed || !this.growing) return;
    this.pollTimer = setTimeout(() => {
      void this.refresh().then(() => this.schedulePoll());
    }, this.pollMs);
  }

  private notify() {
    for (const listener of [...this.listeners]) listener();
  }

  /** Waits until [position] is written; false when it never will be, or when it is a probe far past the end. */
  private async waitFor(position: number): Promise<boolean> {
    const settled = () => {
      if (position < this.length) return true;
      if (!this.growing || position >= this.length + FAR_BYTES) return false;
      return null;
    };
    let state = settled();
    if (state !== null) return state;
    await this.refresh();
    while (!this.closed) {
      state = settled();
      if (state !== null) return state;
      await this.sleep(this.pollMs);
      await this.refresh();
    }
    throw new Error("The file was closed");
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => {
      const wake = () => {
        clearTimeout(timer);
        this.wakers.delete(wake);
        resolve();
      };
      const timer = setTimeout(wake, ms);
      this.wakers.add(wake);
    });
  }

  /** Complete blocks [first, last], fetched where missing (runs of up to 32 per request). */
  private async blocksFor(first: number, last: number): Promise<Uint8Array[]> {
    for (let attempt = 0; ; attempt++) {
      await this.ensure(first, last);
      const blocks: Uint8Array[] = [];
      for (let b = first; b <= last; b++) {
        const block = this.blocks.get(b);
        if (!block) break;
        this.blocks.delete(b);
        this.blocks.set(b, block);
        blocks.push(block);
      }
      if (blocks.length === last - first + 1) return blocks;
      // Dropped from memory by a read ahead meanwhile: fetch again.
      if (attempt >= 2) throw new Error("Couldn't keep the file's blocks in memory");
    }
  }

  private prefetch(from: number, to: number) {
    if (from > to || this.closed) return;
    this.ensure(from, to).catch(() => {});
  }

  private async ensure(first: number, last: number): Promise<void> {
    const waits: Promise<void>[] = [];
    let runStart = -1;
    const flush = (endExclusive: number) => {
      for (let s = runStart; s < endExclusive; s += MAX_BLOCKS_PER_REQUEST) {
        waits.push(this.fetchRun(s, Math.min(endExclusive, s + MAX_BLOCKS_PER_REQUEST) - 1));
      }
      runStart = -1;
    };
    for (let b = first; b <= last; b++) {
      const pending = this.inflight.get(b);
      if (this.blocks.has(b) || pending) {
        if (runStart >= 0) flush(b);
        if (pending) waits.push(pending);
      } else if (runStart < 0) {
        runStart = b;
      }
    }
    if (runStart >= 0) flush(last + 1);
    await Promise.all(waits);
  }

  private fetchRun(first: number, last: number): Promise<void> {
    const job = (async () => {
      const data = await this.fetchBytes(first * BLOCK, (last + 1) * BLOCK);
      if (this.closed) return;
      for (let b = first; b <= last; b++) {
        const from = (b - first) * BLOCK;
        if (from + BLOCK > data.length) break;
        this.blocks.set(b, data.slice(from, from + BLOCK));
      }
      while (this.blocks.size > CACHE_BLOCKS) this.blocks.delete(this.blocks.keys().next().value!);
    })();
    for (let b = first; b <= last; b++) this.inflight.set(b, job);
    job
      .finally(() => {
        for (let b = first; b <= last; b++) if (this.inflight.get(b) === job) this.inflight.delete(b);
      })
      .catch(() => {});
    return job;
  }

  /** Bytes [from, to), which must be written already: the server answers at once instead of waiting for more. */
  private async fetchBytes(from: number, to: number): Promise<Uint8Array> {
    const response = await this.fetchFn(this.opts.url, {
      headers: { ...this.opts.headers, Range: `bytes=${from}-${to - 1}` },
      signal: this.controller.signal,
    });
    if (!response.ok) throw new HttpError(response.status, `${response.status} ${response.statusText}`.trim());
    const data = new Uint8Array(await response.arrayBuffer());
    // A server that ignores the range answers 200 with the whole file: take our part of it.
    return response.status === 206 ? data.subarray(0, to - from) : data.subarray(from, to);
  }
}
