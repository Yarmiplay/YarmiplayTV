import { Fingerprinter, SAMPLE_RATE } from "./fingerprinter";

// For the tests only: Synth.kt, and kotlin.random's XorWow so the same seeds make the same music as the Kotlin's.

/** kotlin.random.Random (XorWowRandom), for the same numbers from the same seeds. */
export class KotlinRandom {
  private x: number;
  private y: number;
  private z = 0;
  private w = 0;
  private v: number;
  private addend: number;

  private constructor(seed1: number, seed2: number) {
    this.x = seed1 | 0;
    this.y = seed2 | 0;
    this.v = ~seed1;
    this.addend = (seed1 << 10) ^ (seed2 >>> 4);
    for (let i = 0; i < 64; i++) this.nextInt();
  }

  /** Random(seed: Int). */
  static ofInt(seed: number): KotlinRandom {
    return new KotlinRandom(seed | 0, (seed | 0) >> 31);
  }

  /** Random(seed: Long), for a whole number within ±2^53. */
  static ofLong(seed: number): KotlinRandom {
    const big = BigInt(seed);
    return new KotlinRandom(Number(BigInt.asIntN(32, big)), Number(BigInt.asIntN(32, big >> 32n)));
  }

  nextInt(): number;
  /** nextInt(until): 0 until [until]. */
  nextInt(until: number): number;
  nextInt(until?: number): number {
    if (until === undefined) return this.next();
    const n = until;
    if ((n & -n) === n) return this.nextBits(31 - Math.clz32(n));
    let bits: number;
    let v: number;
    do {
      bits = this.next() >>> 1;
      v = bits % n;
    } while (((bits - v + (n - 1)) | 0) < 0);
    return v;
  }

  nextDouble(): number {
    const hi = this.nextBits(26);
    const lo = this.nextBits(27);
    return (hi * 134217728 + lo) / 9007199254740992;
  }

  private nextBits(bitCount: number): number {
    return (this.next() >>> (32 - bitCount)) & (-bitCount >> 31);
  }

  private next(): number {
    let t = this.x;
    t = t ^ (t >>> 2);
    this.x = this.y;
    this.y = this.z;
    this.z = this.w;
    const v0 = this.v;
    this.w = v0;
    t = t ^ (t << 1) ^ v0 ^ (v0 << 4);
    this.v = t;
    this.addend = (this.addend + 362437) | 0;
    return (t + this.addend) | 0;
  }
}

const toShort = (x: number) => (Math.trunc(x) << 16) >> 16;

/** Made-up music for the tests: a different chord every [chordSeconds], the same for the same [seed]. */
export const Synth = {
  RATE: SAMPLE_RATE,

  music(seed: number, seconds: number, rate = SAMPLE_RATE, chordSeconds = 0.45, gain = 0.25): Int16Array {
    return Synth.musicAt(seed, 0, Math.trunc(seconds * rate), rate, chordSeconds, gain);
  },

  /** [count] samples of [seed]'s music from sample [from] on: the same samples wherever they are asked from. */
  musicAt(seed: number, from: number, count: number, rate = SAMPLE_RATE, chordSeconds = 0.45, gain = 0.25): Int16Array {
    const samples = new Int16Array(count);
    const perChord = Math.trunc(chordSeconds * rate);
    let chord = -1;
    let freqs: number[] = [];
    for (let k = 0; k < count; k++) {
      const i = from + k;
      const c = Math.floor(i / perChord);
      if (c !== chord) {
        chord = c;
        const random = KotlinRandom.ofLong(seed * 1_000_003 + c * 7919);
        const root = 40 + random.nextInt(24);
        const notes = [root, root + 3 + random.nextInt(2), root + 7, root + 12 - random.nextInt(3) * 2];
        freqs = notes.map((note) => 440.0 * Math.pow(2.0, (note - 69) / 12.0));
      }
      const inChord = i - chord * perChord;
      const t = inChord / rate;
      const envelope = Math.min(1.0, inChord / (0.02 * rate)) * (1.0 - (0.5 * inChord) / perChord);
      let v = 0.0;
      for (const f of freqs) v += Math.sin(2 * Math.PI * f * t) + 0.4 * Math.sin(4 * Math.PI * f * t) + 0.2 * Math.sin(6 * Math.PI * f * t);
      samples[k] = toShort((v / freqs.length) * envelope * gain * 32767);
    }
    return samples;
  },

  noise(seed: number, count: number, gain: number): Int16Array {
    const random = KotlinRandom.ofInt(seed);
    const out = new Int16Array(count);
    for (let i = 0; i < count; i++) out[i] = toShort((random.nextDouble() * 2 - 1) * gain * 32767);
    return out;
  },

  mix(a: Int16Array, b: Int16Array): Int16Array {
    const out = new Int16Array(a.length);
    for (let i = 0; i < a.length; i++) out[i] = Math.min(Math.max(a[i] + (i < b.length ? b[i] : 0), -32768), 32767);
    return out;
  },

  concat(...parts: Int16Array[]): Int16Array {
    const all = new Int16Array(parts.reduce((sum, p) => sum + p.length, 0));
    let at = 0;
    for (const p of parts) {
      all.set(p, at);
      at += p.length;
    }
    return all;
  },

  silence(seconds: number, rate = SAMPLE_RATE): Int16Array {
    return new Int16Array(Math.trunc(seconds * rate));
  },

  fingerprint(pcm: Int16Array, chunk = 4000): Int32Array {
    const f = new Fingerprinter();
    for (let i = 0; i < pcm.length; i += chunk) {
      const n = Math.min(chunk, pcm.length - i);
      f.consume(pcm.slice(i, i + n), n);
    }
    return f.finish();
  },
};

/**
 * A signal made with whole numbers only (square waves and a little noise, a new chord every [chord] samples), so it is
 * the same to the bit in any language: the Kotlin made kotlinGolden.json's fingerprints from the same samples.
 */
export function intSignal(seed: number, count: number, channels = 1, chord = 4961): Int16Array {
  const out = new Int16Array(count * channels);
  let state = seed | 0;
  const step = () => (state = (Math.imul(state, 1103515245) + 12345) | 0);
  let p1 = 1;
  let p2 = 1;
  let p3 = 1;
  for (let i = 0; i < count; i++) {
    if (i % chord === 0) {
      p1 = ((step() >>> 16) % 200) + 20;
      p2 = ((step() >>> 16) % 200) + 20;
      p3 = ((step() >>> 16) % 200) + 20;
    }
    const noise = ((step() >>> 20) % 512) - 256;
    const v =
      (i % p1 < Math.trunc(p1 / 2) ? 3000 : -3000) +
      (i % p2 < Math.trunc(p2 / 2) ? 2000 : -2000) +
      (i % p3 < Math.trunc(p3 / 2) ? 1500 : -1500) +
      noise;
    for (let c = 0; c < channels; c++) out[i * channels + c] = v + c * 7;
  }
  return out;
}

/** The Kotlin test's hash of samples: h = h * 31 + sample, as 32-bit Ints. */
export function shortsHash(a: Int16Array): number {
  let h = 0;
  for (let i = 0; i < a.length; i++) h = (Math.imul(h, 31) + a[i]) | 0;
  return h;
}

/** The mean number of bits two fingerprints differ by, point for point. */
export function meanBitErrors(a: Int32Array, b: Int32Array, bitCount: (x: number) => number): number {
  const n = Math.min(a.length, b.length);
  let sum = 0;
  for (let i = 0; i < n; i++) sum += bitCount(a[i] ^ b[i]);
  return sum / n;
}
