/// <reference lib="webworker" />
/**
 * The player's audio output, on the audio thread: it plays the decoded stereo sound it's sent, and it is the player's
 * clock: it reports which moment of the file is playing. At a speed other than 1 (the sync engine's 0.95× catch-up) it
 * stretches the sound with WSOLA, so it plays slower without dropping in pitch.
 */

declare const sampleRate: number;
declare const currentTime: number;
declare function registerProcessor(name: string, processor: unknown): void;
declare class AudioWorkletProcessor {
  readonly port: MessagePort;
}

type Message =
  | { type: "chunk"; time: number; left: Float32Array; right: Float32Array }
  | { type: "flush"; startAt: number }
  | { type: "rate"; rate: number }
  | { type: "volume"; volume: number };

/** WSOLA frame, and the hop between output frames. */
const N = 1024;
const HS = N / 2;
/** How far either side of the nominal input position WSOLA looks for the best match. */
const TOLERANCE = 256;
const WINDOW = Float32Array.from({ length: N }, (_, i) => 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / N));
/** How often the clock is reported, in seconds. */
const REPORT_EVERY = 0.05;

/** A growable FIFO of samples. */
class Fifo {
  data = new Float32Array(1 << 16);
  start = 0;
  end = 0;

  get length() {
    return this.end - this.start;
  }

  push(samples: Float32Array) {
    if (this.end + samples.length > this.data.length) {
      const live = this.end - this.start;
      if (live + samples.length <= this.data.length) {
        this.data.copyWithin(0, this.start, this.end);
      } else {
        const next = new Float32Array((live + samples.length) * 2);
        next.set(this.data.subarray(this.start, this.end));
        this.data = next;
      }
      this.start = 0;
      this.end = live;
    }
    this.data.set(samples, this.end);
    this.end += samples.length;
  }

  /** The sample [i] places from the front. */
  at(i: number) {
    return this.data[this.start + i];
  }

  drop(count: number) {
    this.start = Math.min(this.end, this.start + count);
    if (this.start === this.end) this.start = this.end = 0;
  }

  clear() {
    this.start = this.end = 0;
  }
}

class PlayerAudio extends AudioWorkletProcessor {
  private readonly input = [new Fifo(), new Fifo()];
  /** File time of the first sample in [input]; null until the first chunk. */
  private inputTime: number | null = null;
  /** Sound before this file time is dropped (after a seek). */
  private startAt = 0;
  private rate = 1;
  private volume = 1;

  // WSOLA state.
  private readonly overlap = [new Float32Array(N), new Float32Array(N)];
  private readonly output = [new Fifo(), new Fifo()];
  /** File time of the first sample in [output]. */
  private outputTime = 0;
  /** Input position (samples from the front of [input]) of the previous frame taken, and the nominal next one. */
  private previous = -1;
  private nominal = 0;

  private lastReport = 0;
  private playing = false;

  constructor() {
    super();
    this.port.onmessage = (event: MessageEvent<Message>) => this.receive(event.data);
  }

  private receive(message: Message) {
    switch (message.type) {
      case "chunk": {
        let { left, right, time } = message;
        if (this.inputTime === null) {
          // Trim what comes before the seek target.
          const skip = Math.round((this.startAt - time) * sampleRate);
          if (skip >= left.length) return;
          if (skip > 0) {
            left = left.subarray(skip);
            right = right.subarray(skip);
            time += skip / sampleRate;
          }
          this.inputTime = time;
          this.outputTime = time;
        }
        this.input[0].push(left);
        this.input[1].push(right);
        break;
      }
      case "flush":
        this.input.forEach((f) => f.clear());
        this.output.forEach((f) => f.clear());
        this.overlap.forEach((o) => o.fill(0));
        this.inputTime = null;
        this.startAt = message.startAt;
        this.previous = -1;
        this.nominal = 0;
        this.playing = false;
        this.report(true);
        break;
      case "rate":
        if (message.rate !== this.rate) {
          this.rate = message.rate;
          this.restartStretch();
        }
        break;
      case "volume":
        this.volume = message.volume;
        break;
    }
  }

  /** Starts WSOLA afresh from where the output has got to, as when the speed changes. */
  private restartStretch() {
    // Input up to the nominal position has been played already, stretched.
    const played = Math.min(Math.floor(this.nominal), this.input[0].length);
    if (played > 0 && this.inputTime !== null) {
      this.input[0].drop(played);
      this.input[1].drop(played);
      this.inputTime += played / sampleRate;
    }
    this.overlap.forEach((o) => o.fill(0));
    this.previous = -1;
    this.nominal = 0;
  }

  /** File time of what's being output now. */
  private get mediaTime(): number {
    return this.outputTime;
  }

  private report(force = false) {
    if (!force && currentTime - this.lastReport < REPORT_EVERY) return;
    this.lastReport = currentTime;
    const buffered = this.inputTime === null ? 0 : this.input[0].length / sampleRate;
    this.port.postMessage({
      type: "clock",
      mediaTime: this.inputTime === null ? this.startAt : this.mediaTime,
      contextTime: currentTime,
      playing: this.playing,
      buffered,
      rate: this.rate,
    });
  }

  process(_inputs: Float32Array[][], outputs: Float32Array[][]): boolean {
    const out = outputs[0];
    const frames = out[0].length;
    const produced = this.rate === 1 ? this.direct(out, frames) : this.stretched(out, frames);
    for (let c = 0; c < out.length; c++) out[c].fill(0, produced);
    this.playing = produced > 0;
    this.report();
    return true;
  }

  /** At normal speed: samples straight from the input. */
  private direct(out: Float32Array[], frames: number): number {
    // Leftovers of a stretch at the old speed go first.
    let written = this.drain(out, 0, frames);
    if (written === frames || this.inputTime === null) return written;
    const take = Math.min(frames - written, this.input[0].length);
    for (let c = 0; c < out.length; c++) {
      const fifo = this.input[Math.min(c, 1)];
      for (let i = 0; i < take; i++) out[c][written + i] = fifo.at(i) * this.volume;
    }
    this.input[0].drop(take);
    this.input[1].drop(take);
    this.inputTime += take / sampleRate;
    this.outputTime = this.inputTime;
    return written + take;
  }

  /** Samples already stretched, from the output FIFO. */
  private drain(out: Float32Array[], from: number, frames: number): number {
    const take = Math.min(frames - from, this.output[0].length);
    if (take <= 0) return from;
    for (let c = 0; c < out.length; c++) {
      const fifo = this.output[Math.min(c, 1)];
      for (let i = 0; i < take; i++) out[c][from + i] = fifo.at(i) * this.volume;
    }
    this.output[0].drop(take);
    this.output[1].drop(take);
    // Output at speed r covers r times its own length of the file.
    this.outputTime += (take / sampleRate) * this.rate;
    return from + take;
  }

  /** At another speed: WSOLA frames until there's enough output. */
  private stretched(out: Float32Array[], frames: number): number {
    while (this.output[0].length < frames && this.stretchOnce()) {
      // One more frame of output.
    }
    return this.drain(out, 0, frames);
  }

  /** One WSOLA step: picks the input frame near the nominal position that best continues the last one. */
  private stretchOnce(): boolean {
    if (this.inputTime === null) return false;
    const base = Math.round(this.nominal);
    const low = Math.max(0, base - TOLERANCE);
    const high = base + TOLERANCE;
    if (high + N > this.input[0].length) return false;

    let best = base;
    if (this.previous >= 0) {
      // The natural continuation of the previous frame, compared on a quarter of the samples to stay cheap.
      const natural = this.previous + HS;
      let bestScore = -Infinity;
      for (let candidate = low; candidate <= high; candidate += 2) {
        let score = 0;
        for (let i = 0; i < N; i += 4) {
          const a = this.input[0].at(natural + i) + this.input[1].at(natural + i);
          const b = this.input[0].at(candidate + i) + this.input[1].at(candidate + i);
          score += a * b;
        }
        if (score > bestScore) {
          bestScore = score;
          best = candidate;
        }
      }
    }

    for (let c = 0; c < 2; c++) {
      const o = this.overlap[c];
      const fifo = this.input[c];
      for (let i = 0; i < N; i++) o[i] += fifo.at(best + i) * WINDOW[i];
      this.output[c].push(o.slice(0, HS));
      o.copyWithin(0, HS);
      o.fill(0, N - HS);
    }

    this.previous = best;
    this.nominal += HS * this.rate;
    // Let go of input that no frame can reach any more.
    const done = Math.max(0, Math.min(this.previous, Math.floor(this.nominal) - TOLERANCE));
    if (done > 0) {
      this.input[0].drop(done);
      this.input[1].drop(done);
      this.inputTime! += done / sampleRate;
      this.previous -= done;
      this.nominal -= done;
    }
    return true;
  }
}

registerProcessor("player-audio", PlayerAudio);
