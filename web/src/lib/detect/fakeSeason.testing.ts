import type { ByteSource } from "../player/bytes";
import type { AudioDecoder, DecodedSound, DecodeOptions, EpisodeRef } from "./audioDecoder";
import { episodeRef, nearestFirst, type SiblingFinder } from "./episodeSiblings";
import type { PlayingEpisode } from "./skipDetector";
import { Synth } from "./synth.testing";

// For the tests only: FakeSeason.kt, with an AudioDecoder that makes the sound up.

const CHUNK = 8192;
const OPENING_SEED = 7;
const ENDING_SEED = 8;

const pad = (n: number) => String(n).padStart(2, "0");

/** Synth's chords made so far, by seed and chord: the tests read the same episodes' music over and over. */
const chords = new Map<string, Int16Array>();
const PER_CHORD = Math.trunc(0.45 * Synth.RATE);

/** Synth.musicAt(seed, from, count), from the chords made already where it can. */
function musicAt(seed: number, from: number, count: number): Int16Array {
  const out = new Int16Array(count);
  for (let k = 0; k < count; ) {
    const c = Math.floor((from + k) / PER_CHORD);
    const key = `${seed}:${c}`;
    let chord = chords.get(key);
    if (!chord) {
      if (chords.size > 20_000) chords.clear();
      chord = Synth.musicAt(seed, c * PER_CHORD, PER_CHORD);
      chords.set(key, chord);
    }
    const offset = from + k - c * PER_CHORD;
    const take = Math.min(PER_CHORD - offset, count - k);
    out.set(chord.subarray(offset, offset + take), k);
    k += take;
  }
  return out;
}

/** A ByteSource with no bytes: the fake decoder knows the episode by its [name] (its address). */
class NamedSource implements ByteSource {
  readonly size = 0;
  closed = false;
  constructor(readonly name: string) {}
  async read(): Promise<Uint8Array> {
    return new Uint8Array(0);
  }
  close() {
    this.closed = true;
  }
}

export interface FakeSeasonOptions {
  count?: number;
  duration?: number;
  songSeconds?: number;
  remote?: boolean;
  openingAt?: (n: number) => number;
  endingAt?: (n: number) => number;
}

/**
 * A made-up season of [count] episodes of [duration] seconds, each its own music with the same opening song at
 * [openingAt] and the same ending song at [endingAt] seconds, each [songSeconds] long. Episodes are at
 * "file:///show/Show - NN.mkv", or on a server ("http://server/NN") when [remote].
 */
export class FakeSeason {
  readonly count: number;
  readonly duration: number;
  readonly songSeconds: number;
  readonly remote: boolean;
  readonly openingAt: (n: number) => number;
  readonly endingAt: (n: number) => number;
  /** Each read: the episode and where it started. */
  readonly reads: [number, number][] = [];
  /** While set, reading waits until cancelled (for the tests of cancelling). */
  hang = false;
  sawCancel = false;
  readonly opened: NamedSource[] = [];

  constructor(options: FakeSeasonOptions = {}) {
    this.count = options.count ?? 4;
    this.duration = options.duration ?? 1440.0;
    this.songSeconds = options.songSeconds ?? 90.0;
    this.remote = options.remote ?? false;
    this.openingAt = options.openingAt ?? ((n) => 10.0 * n);
    this.endingAt = options.endingAt ?? (() => 1300.0);
  }

  url(n: number): string {
    return this.remote ? `http://server/${pad(n)}` : `file:///show/Show - ${pad(n)}.mkv`;
  }

  name(n: number): string {
    return `Show - ${pad(n)}.mkv`;
  }

  private numberOf(url: string): number {
    return Number(/(\d{2})(\.mkv)?$/.exec(url)![1]);
  }

  private open(n: number): Promise<ByteSource> {
    const source = new NamedSource(this.url(n));
    this.opened.push(source);
    return Promise.resolve(source);
  }

  file(n: number): EpisodeRef {
    return episodeRef({
      number: n,
      url: this.url(n),
      fileName: this.name(n),
      sizeBytes: 1000 + n,
      duration: this.remote ? this.duration : null,
      remote: this.remote,
      open: () => this.open(n),
    });
  }

  readonly siblings: SiblingFinder<PlayingEpisode> = {
    of: async (playing) => {
      const n = this.numberOf(playing.url);
      const others: EpisodeRef[] = [];
      for (let k = 1; k <= this.count; k++) if (k !== n) others.push(this.file(k));
      return { number: n, others: nearestFirst(n, others) };
    },
  };

  nowPlaying(n: number): PlayingEpisode {
    return { fileName: this.name(n), sizeBytes: 1000 + n, url: this.url(n), remote: this.remote, open: () => this.open(n) };
  }

  readonly decoder: AudioDecoder = {
    duration: async () => this.duration,
    decodeMono: async (source: ByteSource, start: number, seconds: number, options: DecodeOptions): Promise<DecodedSound | null> => {
      const n = this.numberOf(source.name);
      this.reads.push([n, start]);
      while (this.hang) {
        if (options.signal.aborted) {
          this.sawCancel = true;
          return null;
        }
        await new Promise((resolve) => setTimeout(resolve, 10));
      }
      return { start, samples: this.sound(n, start, seconds, options.signal) };
    },
  };

  private async *sound(n: number, start: number, seconds: number, signal: AbortSignal): AsyncGenerator<Int16Array> {
    const rate = Synth.RATE;
    const first = Math.trunc(start * rate);
    const last = Math.trunc(Math.min(start + seconds, this.duration) * rate);
    const song = Math.trunc(this.songSeconds * rate);
    const op = Math.trunc(this.openingAt(n) * rate);
    const ed = Math.trunc(this.endingAt(n) * rate);
    for (let i = first; i < last; i += CHUNK) {
      if (signal.aborted) return;
      const chunk = Math.min(CHUNK, last - i);
      const pcm = new Int16Array(chunk);
      let k = 0;
      // Each stretch is the opening, the ending or the episode's own music.
      while (k < chunk) {
        const at = i + k;
        let seed: number;
        let from: number;
        let until: number;
        if (at >= op && at < op + song) [seed, from, until] = [OPENING_SEED, at - op, op + song];
        else if (at >= ed && at < ed + song) [seed, from, until] = [ENDING_SEED, at - ed, ed + song];
        else [seed, from, until] = [100 + n, at, Math.min(...[op, ed, i + chunk].filter((x) => x > at))];
        const take = Math.min(until - at, chunk - k);
        pcm.set(musicAt(seed, from, take), k);
        k += take;
      }
      yield pcm;
      // Lets the page breathe, as a real decoder's reads do.
      await Promise.resolve();
    }
  }
}
