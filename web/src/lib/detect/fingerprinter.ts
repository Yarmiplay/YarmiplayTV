import { Fft } from "./fft";

/** Mono samples a second the fingerprinter takes. */
export const SAMPLE_RATE = 11025;
const FRAME_SIZE = 4096;
const HOP = FRAME_SIZE - Math.trunc((FRAME_SIZE * 2) / 3);
/** Seconds between fingerprint values. */
export const HOP_SECONDS = HOP / SAMPLE_RATE;
const BANDS = 12;
const MIN_FREQ = 28.0;
const MAX_FREQ = 3520.0;
const SHORT_MAX = 32767;

const WINDOW = new Float64Array(FRAME_SIZE);
for (let i = 0; i < FRAME_SIZE; i++) WINDOW[i] = (0.54 - 0.46 * Math.cos((i * 2.0 * Math.PI) / (FRAME_SIZE - 1))) / SHORT_MAX;
const MIN_INDEX = Math.max(1, Math.round((FRAME_SIZE * MIN_FREQ) / SAMPLE_RATE));
const MAX_INDEX = Math.min(FRAME_SIZE / 2, Math.round((FRAME_SIZE * MAX_FREQ) / SAMPLE_RATE));
/** Which of the 12 notes each spectrum bin falls in (A at 440 Hz is note 0). */
const NOTES = new Int32Array(FRAME_SIZE / 2 + 1);
for (let i = 0; i < NOTES.length; i++) {
  if (i < MIN_INDEX || i >= MAX_INDEX) continue;
  const octave = Math.log(((i * SAMPLE_RATE) / FRAME_SIZE) / (440.0 / 16.0)) / Math.log(2.0);
  NOTES[i] = Math.min(Math.max(Math.trunc(BANDS * (octave - Math.floor(octave))), 0), BANDS - 1);
}
const FILTER = [0.25, 0.75, 1.0, 0.75, 0.25];
const GRAY = [0, 1, 3, 2];

/** Sums of rectangles of the chroma rows: time from row x, notes from column y. */
class IntegralImage {
  private readonly width = BANDS + 1;
  private readonly sums: Float64Array;

  constructor(rows: readonly Float64Array[]) {
    const width = this.width;
    const sums = (this.sums = new Float64Array((rows.length + 1) * width));
    for (let r = 0; r < rows.length; r++) {
      let line = 0;
      const row = rows[r];
      for (let c = 0; c < BANDS; c++) {
        line += row[c];
        sums[(r + 1) * width + c + 1] = sums[r * width + c + 1] + line;
      }
    }
  }

  area(x1: number, y1: number, x2: number, y2: number): number {
    const { sums, width } = this;
    return sums[x2 * width + y2] - sums[x1 * width + y2] - sums[x2 * width + y1] + sums[x1 * width + y1];
  }
}

const cmp = (a: number, b: number): number => Math.log((1.0 + a) / (1.0 + b));

/** One of the 16 two-bit features: a [type] of rectangle comparison over [width] frames and [height] notes from note [y]. */
class Classifier {
  constructor(
    readonly type: number,
    readonly y: number,
    readonly height: number,
    readonly width: number,
    readonly t0: number,
    readonly t1: number,
    readonly t2: number,
  ) {}

  classify(image: IntegralImage, x: number): number {
    const { y, height, width } = this;
    let v: number;
    switch (this.type) {
      case 0:
        v = cmp(image.area(x, y, x + width, y + height), 0.0);
        break;
      case 1: {
        const h2 = height >> 1;
        v = cmp(image.area(x, y + h2, x + width, y + height), image.area(x, y, x + width, y + h2));
        break;
      }
      case 2: {
        const w2 = width >> 1;
        v = cmp(image.area(x + w2, y, x + width, y + height), image.area(x, y, x + w2, y + height));
        break;
      }
      case 3: {
        const w2 = width >> 1;
        const h2 = height >> 1;
        v = cmp(
          image.area(x, y + h2, x + w2, y + height) + image.area(x + w2, y, x + width, y + h2),
          image.area(x, y, x + w2, y + h2) + image.area(x + w2, y + h2, x + width, y + height),
        );
        break;
      }
      case 4: {
        const h3 = Math.trunc(height / 3);
        v = cmp(
          image.area(x, y + h3, x + width, y + 2 * h3),
          image.area(x, y, x + width, y + h3) + image.area(x, y + 2 * h3, x + width, y + height),
        );
        break;
      }
      default: {
        const w3 = Math.trunc(width / 3);
        v = cmp(
          image.area(x + w3, y, x + 2 * w3, y + height),
          image.area(x, y, x + w3, y + height) + image.area(x + 2 * w3, y, x + width, y + height),
        );
      }
    }
    return v < this.t1 ? (v < this.t0 ? 0 : 1) : v < this.t2 ? 2 : 3;
  }
}

/** Chromaprint's "test2" classifiers (its default algorithm): type, y, height, width, then the quantizer's thresholds. */
const CLASSIFIERS: readonly Classifier[] = [
  new Classifier(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
  new Classifier(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
  new Classifier(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
  new Classifier(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
  new Classifier(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
  new Classifier(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
  new Classifier(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
  new Classifier(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
  new Classifier(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
  new Classifier(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
  new Classifier(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
  new Classifier(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
  new Classifier(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
  new Classifier(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
  new Classifier(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
  new Classifier(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
];
const MAX_WIDTH = Math.max(...CLASSIFIERS.map((c) => c.width));

/** The 32-bit value at row [x] of [image]: each classifier's two bits, the first classifier's highest. */
function valueAt(image: IntegralImage, x: number): number {
  let bits = 0;
  for (const c of CLASSIFIERS) bits = (bits << 2) | GRAY[c.classify(image, x)];
  return bits;
}

/** The value silence gets (no notes at all); it says nothing about which sound this is. */
export const SILENCE: number = valueAt(new IntegralImage(Array.from({ length: MAX_WIDTH }, () => new Float64Array(BANDS))), 0);

/**
 * An audio fingerprint in the manner of Chromaprint (AcoustID's, MIT), ported from Fingerprinter.kt: mono
 * [SAMPLE_RATE] Hz sound is cut into overlapping frames, each frame's spectrum is folded into the 12 notes of the
 * octave, and 16 classifiers that look at a few seconds of those notes give one 32-bit number per [HOP_SECONDS]. The
 * same sound gives the same numbers, or numbers a few bits apart, whatever the file it is in. Only the classifiers'
 * constants are Chromaprint's; the numbers are compared only with this app's own, so they needn't match fpcalc's bit
 * for bit. They do match the Android and desktop apps' bit for bit for the same samples (the same doubles, in the same
 * order), so fingerprints in the shared cache format mean the same everywhere.
 *
 * Feed it with [consume] as the sound is decoded, then take the fingerprint with [finish].
 */
export class Fingerprinter {
  private readonly fft = new Fft(FRAME_SIZE);
  private readonly frame = new Float64Array(FRAME_SIZE);
  private filled = 0;
  private readonly windowed = new Float64Array(FRAME_SIZE);
  private readonly power = new Float64Array(FRAME_SIZE / 2 + 1);
  /** The last [FILTER.length] frames' chroma, oldest first, for the smoothing filter. */
  private readonly recent: Float64Array[] = [];
  /** The smoothed and normalized chroma of every frame so far, [BANDS] values each. */
  private readonly rows: Float64Array[] = [];

  /** Adds the first [count] samples of [pcm] (mono, [SAMPLE_RATE] Hz). */
  consume(pcm: Int16Array, count: number = pcm.length): void {
    const frame = this.frame;
    let i = 0;
    while (i < count) {
      const n = Math.min(FRAME_SIZE - this.filled, count - i);
      for (let k = 0; k < n; k++) frame[this.filled + k] = pcm[i + k];
      this.filled += n;
      i += n;
      if (this.filled === FRAME_SIZE) {
        this.processFrame();
        frame.copyWithin(0, HOP, FRAME_SIZE);
        this.filled = FRAME_SIZE - HOP;
      }
    }
  }

  /** The fingerprint of everything consumed: one value per [HOP_SECONDS], the first for the first frame. */
  finish(): Int32Array {
    if (this.rows.length < MAX_WIDTH) return new Int32Array(0);
    const image = new IntegralImage(this.rows);
    const out = new Int32Array(this.rows.length - MAX_WIDTH + 1);
    for (let x = 0; x < out.length; x++) out[x] = valueAt(image, x);
    return out;
  }

  private processFrame(): void {
    const { frame, windowed, power } = this;
    for (let i = 0; i < FRAME_SIZE; i++) windowed[i] = frame[i] * WINDOW[i];
    this.fft.powerSpectrum(windowed, power);
    const chroma = new Float64Array(BANDS);
    for (let i = MIN_INDEX; i < MAX_INDEX; i++) chroma[NOTES[i]] += power[i];
    this.recent.push(chroma);
    if (this.recent.length > FILTER.length) this.recent.shift();
    if (this.recent.length < FILTER.length) return;
    const smoothed = new Float64Array(BANDS);
    this.recent.forEach((row, j) => {
      for (let b = 0; b < BANDS; b++) smoothed[b] += FILTER[j] * row[b];
    });
    let squares = 0;
    for (let b = 0; b < BANDS; b++) squares += smoothed[b] * smoothed[b];
    const norm = Math.sqrt(squares);
    if (norm < 0.01) smoothed.fill(0);
    else for (let b = 0; b < BANDS; b++) smoothed[b] /= norm;
    this.rows.push(smoothed);
  }
}

/** The fingerprint of [pcm] (mono, [SAMPLE_RATE] Hz) in one go. */
export function fingerprint(pcm: Int16Array): Int32Array {
  const f = new Fingerprinter();
  f.consume(pcm);
  return f.finish();
}
