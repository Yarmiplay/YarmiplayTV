import { SAMPLE_RATE } from "./fingerprinter";

const OUT_CHUNK = 4096;
const TAPS = 63;
const SHORT_MIN = -32768;
const SHORT_MAX = 32767;

/** Where [Resampler] sends what it makes: the first [count] samples of [pcm], which it reuses afterwards. */
export type SampleSink = (pcm: Int16Array, count: number) => void;

/**
 * Turns decoded sound of any rate and channel count into the mono [outRate] Hz samples the Fingerprinter takes (a
 * port of Resampler.kt): channels are averaged, a low-pass filter takes away what the new rate can't hold, and samples
 * are read between the filtered ones. Feed it in pieces with [pushInt16] or [pushFloat32]; what it makes goes to [sink]
 * as it is made. An AudioDecoder whose FFmpeg can't resample to 11025 Hz mono itself can hand its frames here.
 */
export class Resampler {
  private readonly step: number;
  private readonly taps: Float64Array;
  private readonly half: number;
  /** Mono input not yet used up; [base] is the stream index of its first sample. */
  private buffer: Float64Array;
  private length = 0;
  private base = 0;
  /** Where in the input, in input samples, the next output sample is. */
  private position = 0;
  private readonly out = new Int16Array(OUT_CHUNK);
  private outCount = 0;

  constructor(
    private readonly inRate: number,
    private readonly channels: number,
    private readonly sink: SampleSink,
    private readonly outRate: number = SAMPLE_RATE,
  ) {
    this.step = inRate / outRate;
    this.taps = lowPass((Math.min(inRate, outRate) * 0.45) / inRate);
    this.half = this.taps.length >> 1;
    this.buffer = new Float64Array(Math.max(4096, this.taps.length * 4));
  }

  /** Adds [frames] frames of interleaved 16-bit [pcm]. */
  pushInt16(pcm: Int16Array, frames: number): void {
    const channels = this.channels;
    const at = this.reserve(frames);
    for (let f = 0; f < frames; f++) {
      let sum = 0;
      for (let c = 0; c < channels; c++) sum += pcm[f * channels + c];
      this.buffer[at + f] = sum / channels;
    }
    this.added(frames);
  }

  /** Adds [frames] frames of interleaved float [pcm] (-1..1). */
  pushFloat32(pcm: Float32Array, frames: number): void {
    const channels = this.channels;
    const at = this.reserve(frames);
    for (let f = 0; f < frames; f++) {
      let sum = 0;
      for (let c = 0; c < channels; c++) sum += pcm[f * channels + c];
      this.buffer[at + f] = (sum / channels) * SHORT_MAX;
    }
    this.added(frames);
  }

  /** Sends what is still waiting; the input's last [half] samples are read as if silence followed. */
  flush(): void {
    const at = this.reserve(this.half);
    this.buffer.fill(0, at, at + this.half);
    this.added(this.half);
    if (this.outCount > 0) this.sink(this.out, this.outCount);
    this.outCount = 0;
  }

  /** Makes room for [frames] more input samples; returns where they go. */
  private reserve(frames: number): number {
    if (this.length + frames > this.buffer.length) {
      const grown = new Float64Array(Math.max(this.buffer.length * 2, this.length + frames));
      grown.set(this.buffer.subarray(0, this.length));
      this.buffer = grown;
    }
    return this.length;
  }

  private added(frames: number): void {
    this.length += frames;
    this.produce();
  }

  private produce(): void {
    const end = this.base + this.length;
    const half = this.half;
    for (;;) {
      const at = Math.floor(this.position);
      if (at + 1 + half >= end) break;
      const frac = this.position - at;
      const a = this.filtered(at);
      const b = this.filtered(at + 1);
      const value = Math.round(a + (b - a) * frac);
      this.out[this.outCount++] = Math.min(Math.max(value, SHORT_MIN), SHORT_MAX);
      if (this.outCount === this.out.length) {
        this.sink(this.out, this.outCount);
        this.outCount = 0;
      }
      this.position += this.step;
    }
    // Keep what the filter still needs around the next output sample.
    const keepFrom = Math.floor(this.position) - half;
    const drop = Math.min(Math.max(keepFrom - this.base, 0), this.length);
    if (drop > 0) {
      this.buffer.copyWithin(0, drop, this.length);
      this.length -= drop;
      this.base += drop;
    }
  }

  /** The low-passed input at stream index [n]; before the start counts as silence. */
  private filtered(n: number): number {
    const { taps, buffer } = this;
    const offset = n - this.half - this.base;
    let sum = 0;
    for (let k = 0; k < taps.length; k++) {
      const i = offset + k;
      if (i >= 0) sum += taps[k] * buffer[i];
    }
    return sum;
  }
}

/** A windowed-sinc low-pass filter passing up to [cutoff] (as a share of the input rate). */
function lowPass(cutoff: number): Float64Array {
  const m = TAPS - 1;
  const taps = new Float64Array(TAPS);
  for (let i = 0; i < TAPS; i++) {
    const x = i - m / 2.0;
    const sinc = x === 0.0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
    taps[i] = sinc * (0.42 - 0.5 * Math.cos((2 * Math.PI * i) / m) + 0.08 * Math.cos((4 * Math.PI * i) / m));
  }
  let sum = 0;
  for (let i = 0; i < TAPS; i++) sum += taps[i];
  for (let i = 0; i < TAPS; i++) taps[i] /= sum;
  return taps;
}
