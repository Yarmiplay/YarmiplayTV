import type { ByteSource } from "../player/bytes";
import type { AudioDecoder, EpisodeRef } from "./audioDecoder";
import { inlineEngine, type FingerprintEngine } from "./engine";
import { episodeRef, type SiblingFinder } from "./episodeSiblings";
import type { FingerprintCache, SkipKind, Span, StoredFingerprint } from "./fingerprintCache";
import { endSeconds, startSeconds } from "./fingerprintMatcher";
import { HOP_SECONDS, SAMPLE_RATE } from "./fingerprinter";
import { Mutex } from "./mutex";

/** A part of an episode that can be skipped: its opening or its ending, in seconds. */
export interface SkipSegment {
  readonly kind: SkipKind;
  readonly start: number;
  readonly end: number;
}

/** The playing episode, as far as the detector needs it: what to call it in the cache, and how to read it. */
export interface PlayingEpisode {
  readonly fileName: string;
  readonly sizeBytes: number;
  readonly url: string;
  /** Streamed from a server rather than a file on this device. */
  readonly remote: boolean;
  open(): Promise<ByteSource>;
}

/** Other episodes compared at most, for each part. */
const MAX_PARTNERS = 4;
/** Two episodes agree on where the opening is when its start and end are this close. */
export const AGREE_SECONDS = 3.0;
/** Shortest and longest opening, and ending. */
const OPENING_SECONDS: readonly [number, number] = [15.0, 120.0];
const ENDING_SECONDS: readonly [number, number] = [15.0, 450.0];
/** A fingerprint shorter than an opening can be says nothing. */
const MIN_POINTS = Math.trunc(15 / HOP_SECONDS);
const KIND_ORDER: readonly SkipKind[] = ["OPENING", "ENDING"];

/** The part of an episode of [duration] seconds listened to for [kind]: from, and how long. */
export function listenWindow(duration: number, kind: SkipKind): [number, number] {
  if (kind === "OPENING") return [0.0, Math.min(duration * 0.25, 600.0)];
  const seconds = Math.min(duration * 0.25, 450.0);
  return [duration - seconds, seconds];
}

const round = (seconds: number): number => Math.round(seconds * 100) / 100.0;

function abortError(signal: AbortSignal | undefined): unknown {
  return signal?.reason ?? new DOMException("Cancelled", "AbortError");
}

export interface SkipDetectorOptions<P> {
  readonly decoder: AudioDecoder;
  readonly cache: FingerprintCache;
  readonly siblings: SiblingFinder<P>;
  /**
   * Whether a server's episodes may be listened to now: the app says yes on an unmetered network, or always when
   * "Listen on any network" is on. Files on this device are always read.
   */
  readonly mayListenRemote: () => boolean;
  /** The audio languages the user prefers, first first (Playback's audio languages). */
  readonly languages?: () => readonly string[];
  /** Where fingerprints are made and compared: a FingerprintWorker in the app; on this thread when left out. */
  readonly engine?: FingerprintEngine;
}

/**
 * Finds an episode's opening and ending by listening (SkipDetector.kt): the start (and the end) of the episode is
 * compared with the same part of the episodes before and after it in its season, and the stretch of sound they share,
 * the opening song (the ending song), is where it is. Two other episodes have to agree on it (one will do in a season
 * of two). What is learned is kept in the cache, so the next episode usually needs only its own sound read, or none.
 *
 * Files on this device are always read; a server's episodes only when [SkipDetectorOptions.mayListenRemote] says so.
 * One file is read at a time.
 */
export class SkipDetector<P extends PlayingEpisode = PlayingEpisode> {
  private readonly reading = new Mutex();
  /** One lock per fingerprint being made, so two asks for the same one read the file once. */
  private readonly making = new Map<string, Mutex>();
  /** Fingerprints that couldn't be made (no such sound, an unknown codec), not tried again this session. */
  private readonly failed = new Set<string>();
  private readonly engine: FingerprintEngine;

  constructor(private readonly options: SkipDetectorOptions<P>) {
    this.engine = options.engine ?? inlineEngine;
  }

  /**
   * Where the playing episode's parts of [kinds] are, as far as listening can tell; [duration] is its length.
   * Rejects with [signal]'s reason (an AbortError) once it aborts, having stopped reading.
   */
  async detect(playing: P, duration: number, kinds: Iterable<SkipKind>, signal?: AbortSignal): Promise<SkipSegment[]> {
    const wanted = new Set(kinds);
    if (wanted.size === 0 || duration <= 0) return [];
    const season = await this.options.siblings.of(playing);
    if (!season) return [];
    this.check(signal);
    const me = episodeRef({
      number: season.number,
      url: playing.url,
      fileName: playing.fileName,
      sizeBytes: playing.sizeBytes,
      duration,
      remote: playing.remote,
      open: () => playing.open(),
    });
    const found: SkipSegment[] = [];
    for (const kind of KIND_ORDER) {
      if (!wanted.has(kind)) continue;
      const part = await this.find(me, season.others, kind, signal);
      if (part) found.push(part);
    }
    return found;
  }

  private async find(me: EpisodeRef, others: readonly EpisodeRef[], kind: SkipKind, signal: AbortSignal | undefined): Promise<SkipSegment | null> {
    const known = await this.options.cache.observations(me.key, kind);
    const heard: Span[][] = [];
    for (const other of others.slice(0, MAX_PARTNERS)) {
      const spans = known[other.key] ?? (await this.compare(me, other, kind, signal));
      if (!spans) continue;
      heard.push(spans);
      const both = agreed(heard);
      if (both) return { kind, start: both.start, end: both.end };
    }
    // A season of two has only the one other episode to go by.
    if (others.length === 1 && heard.length === 1 && heard[0].length > 0) return { kind, start: heard[0][0].start, end: heard[0][0].end };
    return null;
  }

  /** What [me] and [other] share in their [kind] part, also remembered for [other]; null when either can't be read. */
  private async compare(me: EpisodeRef, other: EpisodeRef, kind: SkipKind, signal: AbortSignal | undefined): Promise<Span[] | null> {
    const mine = await this.fingerprint(me, kind, signal);
    if (!mine) return null;
    const theirs = await this.fingerprint(other, kind, signal);
    if (!theirs) return null;
    const [min, max] = kind === "OPENING" ? OPENING_SECONDS : ENDING_SECONDS;
    const runs = await this.engine.sharedRuns(mine.points, theirs.points, Math.round(min / HOP_SECONDS), Math.round(max / HOP_SECONDS));
    this.check(signal);
    const span = (fp: StoredFingerprint, start: number, end: number): Span => ({
      start: round(fp.start + startSeconds(start)),
      end: round(fp.start + endSeconds(end)),
    });
    const forMe = runs.map((r) => span(mine, r.aStart, r.aEnd));
    const forThem = runs.map((r) => span(theirs, r.bStart, r.bEnd));
    await this.options.cache.observe(me.key, other.key, kind, forMe);
    await this.options.cache.observe(other.key, me.key, kind, forThem);
    return forMe;
  }

  private async fingerprint(episode: EpisodeRef, kind: SkipKind, signal: AbortSignal | undefined): Promise<StoredFingerprint | null> {
    const id = `${episode.key}/${kind}`;
    if (this.failed.has(id)) return null;
    const cached = await this.options.cache.fingerprint(episode.key, kind);
    if (cached) return cached;
    if (episode.remote && !this.options.mayListenRemote()) return null;
    let lock = this.making.get(id);
    if (!lock) {
      lock = new Mutex();
      this.making.set(id, lock);
    }
    try {
      return await lock.lock(async () => (await this.options.cache.fingerprint(episode.key, kind)) ?? (await this.make(episode, kind, id, signal)));
    } finally {
      if (this.making.get(id) === lock && !lock.isLocked) this.making.delete(id);
    }
  }

  private make(episode: EpisodeRef, kind: SkipKind, id: string, signal: AbortSignal | undefined): Promise<StoredFingerprint | null> {
    return this.reading.lock(async () => {
      this.check(signal);
      const made = await this.listen(episode, kind, signal);
      // A decoder that stops when cancelled may say it couldn't decode: that isn't the file's fault.
      this.check(signal);
      if (made === null) this.failed.add(id);
      else await this.options.cache.saveFingerprint(episode.key, kind, made);
      return made;
    });
  }

  /** Reads the [kind] part of [episode] and fingerprints it; null when it can't be. Throws only when cancelled. */
  private async listen(episode: EpisodeRef, kind: SkipKind, signal: AbortSignal | undefined): Promise<StoredFingerprint | null> {
    const { decoder } = this.options;
    let source: ByteSource | null = null;
    const session = this.engine.begin();
    let finished = false;
    try {
      source = await episode.open();
      const duration = episode.duration ?? (await decoder.duration(source));
      if (duration == null) return null;
      const [from, seconds] = listenWindow(duration, kind);
      const sound = await decoder.decodeMono(source, from, seconds, {
        sampleRate: SAMPLE_RATE,
        languages: this.options.languages?.() ?? [],
        signal: signal ?? new AbortController().signal,
      });
      if (!sound) return null;
      for await (const pcm of sound.samples) {
        this.check(signal);
        session.consume(pcm);
      }
      this.check(signal);
      finished = true;
      const points = await session.finish();
      return points.length >= MIN_POINTS ? { start: sound.start, points } : null;
    } catch (e) {
      if (signal?.aborted) throw abortError(signal);
      console.warn(`SkipDetector: can't listen to ${episode.fileName}`, e);
      return null;
    } finally {
      if (!finished) session.cancel();
      source?.close();
    }
  }

  private check(signal: AbortSignal | undefined): void {
    if (signal?.aborted) throw abortError(signal);
  }
}

/** The longest stretch two other episodes both share with this one, give or take [AGREE_SECONDS]. */
function agreed(heard: readonly Span[][]): Span | null {
  let best: Span | null = null;
  for (let i = 0; i < heard.length; i++) {
    for (let j = i + 1; j < heard.length; j++) {
      for (const a of heard[i]) {
        for (const b of heard[j]) {
          if (Math.abs(a.start - b.start) > AGREE_SECONDS || Math.abs(a.end - b.end) > AGREE_SECONDS) continue;
          const span = { start: (a.start + b.start) / 2, end: (a.end + b.end) / 2 };
          if (best === null || span.end - span.start > best.end - best.start) best = span;
        }
      }
    }
  }
  return best;
}
