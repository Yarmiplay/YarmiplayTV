/**
 * An in-place radix-2 FFT of [size] points (a power of two), for the power spectrum of real samples: a port of
 * Fft.kt. Its tables and its order of operations are the Kotlin's, so the spectra (and the fingerprints made from
 * them) come out the same to the bit.
 */
export class Fft {
  private readonly re: Float64Array;
  private readonly im: Float64Array;
  private readonly cosTable: Float64Array;
  private readonly sinTable: Float64Array;
  private readonly reversed: Int32Array;

  constructor(private readonly size: number) {
    if (size < 2 || (size & (size - 1)) !== 0) throw new Error("FFT size must be a power of two");
    this.re = new Float64Array(size);
    this.im = new Float64Array(size);
    this.cosTable = new Float64Array(size / 2);
    this.sinTable = new Float64Array(size / 2);
    for (let i = 0; i < size / 2; i++) {
      this.cosTable[i] = Math.cos((2 * Math.PI * i) / size);
      this.sinTable[i] = -Math.sin((2 * Math.PI * i) / size);
    }
    const bits = 31 - Math.clz32(size);
    this.reversed = new Int32Array(size);
    for (let i = 0; i < size; i++) this.reversed[i] = reverseBits(i) >>> (32 - bits);
  }

  /** Writes |X(k)|² of [input] (length [size]) for k = 0..size/2 into [power]. */
  powerSpectrum(input: Float64Array, power: Float64Array): void {
    const { size, re, im, cosTable, sinTable, reversed } = this;
    for (let i = 0; i < size; i++) {
      re[reversed[i]] = input[i];
      im[reversed[i]] = 0;
    }
    let half = 1;
    while (half < size) {
      const step = size / (half * 2);
      for (let start = 0; start < size; start += half * 2) {
        let k = 0;
        for (let j = start; j < start + half; j++) {
          const wr = cosTable[k];
          const wi = sinTable[k];
          const l = j + half;
          const tr = wr * re[l] - wi * im[l];
          const ti = wr * im[l] + wi * re[l];
          re[l] = re[j] - tr;
          im[l] = im[j] - ti;
          re[j] += tr;
          im[j] += ti;
          k += step;
        }
      }
      half *= 2;
    }
    for (let k = 0; k <= size / 2; k++) power[k] = re[k] * re[k] + im[k] * im[k];
  }
}

/** Java's Integer.reverse: the 32 bits of [i] in the other order, as an unsigned number. */
function reverseBits(i: number): number {
  let x = i >>> 0;
  x = ((x >>> 1) & 0x55555555) | ((x & 0x55555555) << 1);
  x = ((x >>> 2) & 0x33333333) | ((x & 0x33333333) << 2);
  x = ((x >>> 4) & 0x0f0f0f0f) | ((x & 0x0f0f0f0f) << 4);
  x = ((x >>> 8) & 0x00ff00ff) | ((x & 0x00ff00ff) << 8);
  x = (x >>> 16) | (x << 16);
  return x >>> 0;
}
