import { describe, expect, it } from "vitest";
import type { ByteSource } from "../player/bytes";
import { bitCount } from "./fingerprintMatcher";
import { SAMPLE_RATE } from "./fingerprinter";
import { audioDecoderFromFrames, type MonoFrame, type MonoFrameReader } from "./frameDecoder";
import { Synth, meanBitErrors } from "./synth.testing";

// The adapter from the player's mono frames (at the file's own rate) to the detector's AudioDecoder.

const source: ByteSource = { size: 0, name: "test", read: async () => new Uint8Array(0), close: () => {} };

describe("audioDecoderFromFrames", () => {
  it("starts where the first frame does and hands over the sound at 11025 Hz", async () => {
    const high = Synth.music(5, 20.0, 44100);
    const reader: MonoFrameReader = async function* (open, from): AsyncGenerator<MonoFrame> {
      expect(await open()).toBe(source);
      for (let i = 0; i < high.length; i += 1152) {
        const piece = high.subarray(i, i + 1152);
        yield { time: from - 0.02 + i / 44100, sampleRate: 44100, samples: Float32Array.from(piece, (s) => s / 32767) };
      }
    };
    const decoder = audioDecoderFromFrames(reader, async () => 20);
    const sound = (await decoder.decodeMono(source, 3, 20, { sampleRate: SAMPLE_RATE, languages: [], signal: new AbortController().signal }))!;
    expect(sound.start).toBeCloseTo(2.98, 10);
    const pieces: Int16Array[] = [];
    for await (const pcm of sound.samples) pieces.push(pcm);
    const out = Synth.concat(...pieces);
    const direct = Synth.music(5, 20.0);
    expect(Math.abs(out.length - direct.length)).toBeLessThanOrEqual(30);
    expect(meanBitErrors(Synth.fingerprint(direct), Synth.fingerprint(out), bitCount)).toBeLessThanOrEqual(4.0);
    expect(await decoder.duration(source)).toBe(20);
  });

  it("no frames, no sound", async () => {
    const decoder = audioDecoderFromFrames(async function* () {}, async () => null);
    expect(await decoder.decodeMono(source, 0, 10, { sampleRate: SAMPLE_RATE, languages: [], signal: new AbortController().signal })).toBeNull();
  });
});
