/**
 * Where the player reads a file's bytes: a file on this device, or a URL read with Range requests (Jellyfin, Plex, a
 * YarmiplayServerTV relay). The demuxer asks for byte ranges in any order, so seeking never needs the whole file.
 */
export interface ByteSource {
  /** Bytes in all. */
  readonly size: number;
  /** A name for logs and errors. */
  readonly name: string;
  /** Up to [length] bytes from [position]; fewer only at the end of the file. */
  read(position: number, length: number): Promise<Uint8Array>;
  close(): void;
}

/** A file picked on this device: read straight from the disk, as slices. */
export class FileSource implements ByteSource {
  constructor(readonly file: Blob & { name?: string }) {}

  get size() {
    return this.file.size;
  }

  get name() {
    return this.file.name ?? "file";
  }

  async read(position: number, length: number): Promise<Uint8Array> {
    const end = Math.min(position + length, this.file.size);
    if (position >= end) return new Uint8Array(0);
    return new Uint8Array(await this.file.slice(position, end).arrayBuffer());
  }

  close() {}
}

export class HttpError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** Bytes of a block, the unit the HTTP source fetches and keeps. */
const BLOCK = 1 << 20;
/** At most this many blocks in one request, as the relay asks (RelayTransfer.kt). */
const MAX_BLOCKS_PER_REQUEST = 32;
/** Blocks kept in memory, least recently used dropped first: 96 MiB. */
const CACHE_BLOCKS = 96;

/**
 * A URL read with Range requests, in 1 MiB blocks kept in a small cache, with the next blocks fetched ahead of what
 * was asked for: a demuxer reads a little at a time, and one request per read would be slow.
 */
export class HttpSource implements ByteSource {
  private readonly cache = new Map<number, Uint8Array>();
  private readonly inflight = new Map<number, Promise<void>>();
  private closed = false;
  private readonly controller = new AbortController();

  private constructor(
    private readonly url: string,
    readonly size: number,
    readonly name: string,
    private readonly headers: Record<string, string>,
    /** Blocks fetched ahead of the last one read. */
    private readonly readAhead: number,
  ) {}

  /** Opens [url], learning its size from a first small Range request. */
  static async open(url: string, opts: { name?: string; headers?: Record<string, string>; readAheadBlocks?: number } = {}) {
    const headers = opts.headers ?? {};
    const response = await fetch(url, { headers: { ...headers, Range: "bytes=0-0" } });
    if (!response.ok) throw new HttpError(response.status, `${response.status} ${response.statusText}`.trim());
    const range = response.headers.get("Content-Range");
    const total = range ? Number(range.split("/")[1]) : Number(response.headers.get("Content-Length"));
    await response.body?.cancel();
    if (!Number.isFinite(total) || total <= 0) throw new Error("The server didn't say how big the file is");
    if (response.status !== 206) {
      // Without ranges every seek would download from the start; the relay and media servers all support them.
      throw new Error("The server doesn't allow reading parts of the file");
    }
    const name = opts.name ?? decodeURIComponent(new URL(url, location.href).pathname.split("/").pop() || "video");
    return new HttpSource(url, total, name, headers, opts.readAheadBlocks ?? 8);
  }

  async read(position: number, length: number): Promise<Uint8Array> {
    const end = Math.min(position + length, this.size);
    if (position >= end) return new Uint8Array(0);
    const first = Math.floor(position / BLOCK);
    const last = Math.floor((end - 1) / BLOCK);
    await this.ensure(first, last);
    this.prefetch(last + 1, last + this.readAhead);
    const out = new Uint8Array(end - position);
    for (let b = first; b <= last; b++) {
      const block = this.cache.get(b)!;
      this.touch(b, block);
      const blockStart = b * BLOCK;
      const from = Math.max(position, blockStart) - blockStart;
      const to = Math.min(end, blockStart + block.length) - blockStart;
      out.set(block.subarray(from, to), blockStart + from - position);
    }
    return out;
  }

  close() {
    this.closed = true;
    this.controller.abort();
    this.cache.clear();
  }

  private touch(index: number, block: Uint8Array) {
    this.cache.delete(index);
    this.cache.set(index, block);
  }

  private prefetch(from: number, to: number) {
    const lastBlock = Math.floor((this.size - 1) / BLOCK);
    to = Math.min(to, lastBlock);
    if (from > to) return;
    this.ensure(from, to).catch(() => {});
  }

  /** Blocks [first, last] in the cache, fetching the missing runs. */
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
      if (this.cache.has(b) || pending) {
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
    const start = first * BLOCK;
    const end = Math.min((last + 1) * BLOCK, this.size) - 1;
    const job = (async () => {
      const response = await fetch(this.url, {
        headers: { ...this.headers, Range: `bytes=${start}-${end}` },
        signal: this.controller.signal,
      });
      if (!response.ok) throw new HttpError(response.status, `${response.status} ${response.statusText}`.trim());
      const data = new Uint8Array(await response.arrayBuffer());
      // A server that ignores the range answers 200 with the whole file: take our part of it.
      const offset = response.status === 206 ? 0 : start;
      if (this.closed) return;
      for (let b = first; b <= last; b++) {
        const from = (b - first) * BLOCK + offset;
        this.cache.set(b, data.slice(from, Math.min(from + BLOCK, data.length)));
      }
      while (this.cache.size > CACHE_BLOCKS) this.cache.delete(this.cache.keys().next().value!);
    })();
    for (let b = first; b <= last; b++) this.inflight.set(b, job);
    job.finally(() => {
      for (let b = first; b <= last; b++) if (this.inflight.get(b) === job) this.inflight.delete(b);
    }).catch(() => {});
    return job;
  }
}
