import { describe, expect, it } from "vitest";
import { bitCount, endSeconds, sharedRuns, startSeconds, runLength } from "./fingerprintMatcher";
import { Fingerprinter, HOP_SECONDS, SAMPLE_RATE, SILENCE } from "./fingerprinter";
import golden from "./kotlinGolden.json";
import { Resampler } from "./resampler";
import { Synth, intSignal, meanBitErrors as meanErrors, shortsHash } from "./synth.testing";

// Ported from FingerprintTest.kt and its Synth.kt. kotlinGolden.json holds what the Kotlin (Fingerprinter.kt,
// Resampler.kt, FingerprintMatcher.kt, Synth.kt, run on the JVM) made from the same samples: the fingerprints have to
// come out the same to the bit, as the apps and the browser share the cache format.

const hop = HOP_SECONDS;
const meanBitErrors = (a: Int32Array, b: Int32Array) => meanErrors(a, b, bitCount);
const list = (a: ArrayLike<number>) => Array.from(a);

function resample(pcm: Int16Array, inRate: number, channels: number, piece: number): Int16Array {
  const out: number[] = [];
  const resampler = new Resampler(inRate, channels, (samples, n) => {
    for (let i = 0; i < n; i++) out.push(samples[i]);
  });
  const frames = pcm.length / channels;
  for (let i = 0; i < frames; i += piece) {
    const n = Math.min(piece, frames - i);
    resampler.pushInt16(pcm.slice(i * channels, (i + n) * channels), n);
  }
  resampler.flush();
  return Int16Array.from(out);
}

describe("the same numbers as the Kotlin", () => {
  it("has the same constants", () => {
    expect(SILENCE).toBe(golden.silence);
    expect(HOP_SECONDS).toBe(golden.hopSeconds);
  });

  it("fingerprints the same samples to the same bits", () => {
    const s1 = intSignal(1, 20 * SAMPLE_RATE);
    expect(shortsHash(s1)).toBe(golden.int1Hash);
    expect(list(Synth.fingerprint(s1))).toEqual(golden.int1Fp);
    expect(list(Synth.fingerprint(intSignal(2, 60 * SAMPLE_RATE), 777))).toEqual(golden.int2Fp);
  });

  it("resamples 48 kHz stereo to the same samples", () => {
    const out = resample(intSignal(3, 15 * 48000, 2, 21600), 48000, 2, 1000);
    expect(out.length).toBe(golden.resCount);
    expect(list(out.subarray(0, 300))).toEqual(golden.resHead);
    expect(shortsHash(out)).toBe(golden.resHash);
    expect(list(Synth.fingerprint(out))).toEqual(golden.resFp);
  });

  it("resamples float samples to the same samples", () => {
    const pcm = intSignal(4, 5 * 44100);
    const floats = new Float32Array(pcm.length);
    for (let i = 0; i < pcm.length; i++) floats[i] = pcm[i] / 32768;
    const out: number[] = [];
    const resampler = new Resampler(44100, 1, (samples, n) => {
      for (let i = 0; i < n; i++) out.push(samples[i]);
    });
    resampler.pushFloat32(floats, floats.length);
    resampler.flush();
    expect(out.length).toBe(golden.resFCount);
    expect(shortsHash(Int16Array.from(out))).toBe(golden.resFHash);
  });

  it("makes the test music the Kotlin makes, and its fingerprint", () => {
    expect(list(Synth.noise(9, 200, 0.008))).toEqual(golden.noise9);
    const music = Synth.music(1, 20.0);
    expect(list(music.subarray(0, 200))).toEqual(golden.synth1Head);
    expect(shortsHash(music)).toBe(golden.synth1Hash);
    expect(list(Synth.fingerprint(music))).toEqual(golden.synth1Fp);
  });

  it("finds the same runs", () => {
    const theme = Synth.music(100, 90.0);
    const a = Synth.concat(Synth.music(1, 60.0), theme, Synth.music(2, 40.0));
    const b = Synth.concat(Synth.music(3, 20.0), theme, Synth.music(4, 70.0));
    const runs = sharedRuns(Synth.fingerprint(a), Synth.fingerprint(Synth.mix(b, Synth.noise(5, b.length, 0.005))), Math.round(15 / hop), Math.round(120 / hop));
    expect(runs.map((r) => [r.aStart, r.aEnd, r.bStart, r.bEnd])).toEqual(golden.sharedRuns);
  });
});

describe("FingerprintTest", () => {
  it("same sound, same numbers", () => {
    const pcm = Synth.music(1, 20.0);
    const fp = Synth.fingerprint(pcm);
    expect(fp.length).toBeGreaterThan(130);
    expect(list(Synth.fingerprint(pcm))).toEqual(list(fp));
  });

  it("piece size does not matter", () => {
    const pcm = Synth.music(2, 15.0);
    expect(list(Synth.fingerprint(pcm, 333))).toEqual(list(Synth.fingerprint(pcm, pcm.length)));
  });

  it("noise changes few bits and other music many", () => {
    const pcm = Synth.music(3, 30.0);
    const fp = Synth.fingerprint(pcm);
    const noisy = Synth.fingerprint(Synth.mix(pcm, Synth.noise(9, pcm.length, 0.008)));
    const other = Synth.fingerprint(Synth.music(4, 30.0));
    expect(meanBitErrors(fp, noisy)).toBeLessThanOrEqual(4.0);
    expect(meanBitErrors(fp, other)).toBeGreaterThanOrEqual(12.0);
  });

  it("silence is one value", () => {
    const fp = Synth.fingerprint(Synth.silence(10.0));
    expect(fp.length).toBeGreaterThan(0);
    expect(fp.every((v) => v === SILENCE)).toBe(true);
  });

  it("finds the shared song wherever it is", () => {
    const theme = Synth.music(100, 90.0);
    const a = Synth.concat(Synth.music(1, 60.0), theme, Synth.music(2, 40.0));
    const b = Synth.concat(Synth.music(3, 20.0), theme, Synth.music(4, 70.0));
    const runs = sharedRuns(Synth.fingerprint(a), Synth.fingerprint(Synth.mix(b, Synth.noise(5, b.length, 0.005))), Math.round(15 / hop), Math.round(120 / hop));
    expect(runs.length).toBeGreaterThan(0);
    const run = runs[0];
    expect(Math.abs(startSeconds(run.aStart) - 60.0)).toBeLessThanOrEqual(0.5);
    expect(Math.abs(endSeconds(run.aEnd) - 150.0)).toBeLessThanOrEqual(0.5);
    expect(Math.abs(startSeconds(run.bStart) - 20.0)).toBeLessThanOrEqual(0.5);
    expect(Math.abs(endSeconds(run.bEnd) - 110.0)).toBeLessThanOrEqual(0.5);
  });

  it("a short shared bit is no opening", () => {
    const jingle = Synth.music(100, 8.0);
    const a = Synth.concat(Synth.music(1, 30.0), jingle, Synth.music(2, 30.0));
    const b = Synth.concat(Synth.music(3, 50.0), jingle, Synth.music(4, 30.0));
    expect(sharedRuns(Synth.fingerprint(a), Synth.fingerprint(b), Math.round(15 / hop), Math.round(120 / hop))).toEqual([]);
  });

  it("different episodes share nothing", () => {
    const runs = sharedRuns(Synth.fingerprint(Synth.music(1, 120.0)), Synth.fingerprint(Synth.music(2, 120.0)), 1, 10_000);
    expect(runs.filter((r) => runLength(r) * hop > 3)).toEqual([]);
  });

  it("bridges a line spoken over the song", () => {
    const theme = Synth.music(100, 90.0);
    const spoken = theme.slice();
    // Two seconds of something else over the song, halfway through.
    spoken.set(Synth.music(77, 2.0, SAMPLE_RATE, 0.45, 0.6), 45 * SAMPLE_RATE);
    const a = Synth.concat(Synth.music(1, 30.0), theme, Synth.music(2, 30.0));
    const b = Synth.concat(Synth.music(3, 30.0), spoken, Synth.music(4, 30.0));
    const run = sharedRuns(Synth.fingerprint(a), Synth.fingerprint(b), Math.round(15 / hop), Math.round(120 / hop))[0];
    expect(run).toBeDefined();
    expect(Math.abs(runLength(run) * hop - 90)).toBeLessThan(3);
  });

  it("the resampler keeps the sound", () => {
    // 48 kHz stereo made into 11025 Hz mono fingerprints like music made at 11025 Hz.
    const direct = Synth.music(5, 20.0);
    const high = Synth.music(5, 20.0, 48_000);
    const stereo = new Int16Array(high.length * 2);
    for (let i = 0; i < stereo.length; i++) stereo[i] = high[i >> 1];
    const out = resample(stereo, 48_000, 2, 1000);
    expect(Math.abs(direct.length - out.length)).toBeLessThanOrEqual(30);
    expect(meanBitErrors(Synth.fingerprint(direct), Synth.fingerprint(out))).toBeLessThanOrEqual(4.0);
  });
});

describe("a tone sequence two signals share", () => {
  /** [seconds] of pure tones, a new one every half second, picked by [seed]: the same tones for the same seed. */
  function tones(seed: number, seconds: number, gain = 0.3): Int16Array {
    const out = new Int16Array(Math.round(seconds * SAMPLE_RATE));
    const per = Math.trunc(SAMPLE_RATE / 2);
    let freq = 0;
    let state = seed;
    for (let i = 0; i < out.length; i++) {
      if (i % per === 0) {
        state = (Math.imul(state, 1103515245) + 12345) | 0;
        freq = 110 * Math.pow(2, ((state >>> 16) % 36) / 12);
      }
      out[i] = Math.round(Math.sin((2 * Math.PI * freq * i) / SAMPLE_RATE) * gain * 32767);
    }
    return out;
  }

  it("finds the shared 30 s within 3 s in both", () => {
    const shared = tones(42, 30);
    const a = Synth.concat(Synth.music(11, 25), shared, Synth.music(12, 20));
    const b = Synth.concat(Synth.music(13, 50), shared, Synth.music(14, 10));
    const fp = new Fingerprinter();
    fp.consume(a);
    const runs = sharedRuns(fp.finish(), Synth.fingerprint(b), Math.round(15 / hop), Math.round(120 / hop));
    expect(runs.length).toBeGreaterThan(0);
    const run = runs[0];
    expect(Math.abs(startSeconds(run.aStart) - 25)).toBeLessThanOrEqual(3);
    expect(Math.abs(endSeconds(run.aEnd) - 55)).toBeLessThanOrEqual(3);
    expect(Math.abs(startSeconds(run.bStart) - 50)).toBeLessThanOrEqual(3);
    expect(Math.abs(endSeconds(run.bEnd) - 80)).toBeLessThanOrEqual(3);
  });
});
