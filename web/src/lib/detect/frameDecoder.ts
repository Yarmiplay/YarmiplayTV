import type { ByteSource } from "../player/bytes";
import type { AudioDecoder, DecodedSound, DecodeOptions } from "./audioDecoder";
import { Resampler } from "./resampler";

/** Decoded mono sound at the file's own rate, as the player's audio extraction hands it over. */
export interface MonoFrame {
  /** Seconds into the file of the first sample. */
  readonly time: number;
  readonly sampleRate: number;
  /** Mono, -1..1. */
  readonly samples: Float32Array;
}

/** Reads [duration] seconds of mono frames from [from] on (the shape of the player's audioExtract decodeMono). */
export type MonoFrameReader = (
  open: () => Promise<ByteSource>,
  from: number,
  duration: number,
  options: { languages?: readonly string[]; signal?: AbortSignal },
) => AsyncIterable<MonoFrame>;

/**
 * An [AudioDecoder] over a reader of mono frames at the file's own rate: the first frame's time is where the sound
 * starts, and the frames are resampled to the detector's rate with the [Resampler]. [duration] says how long a file is
 * (the demuxer knows).
 */
export function audioDecoderFromFrames(read: MonoFrameReader, duration: (source: ByteSource) => Promise<number | null>): AudioDecoder {
  return {
    duration,
    async decodeMono(source: ByteSource, fromSeconds: number, seconds: number, options: DecodeOptions): Promise<DecodedSound | null> {
      const frames = read(async () => source, fromSeconds, seconds, { languages: options.languages, signal: options.signal })[Symbol.asyncIterator]();
      const first = await frames.next();
      if (first.done) return null;
      return { start: first.value.time, samples: resampled(first.value, frames, options.sampleRate) };
    },
  };
}

async function* resampled(first: MonoFrame, rest: AsyncIterator<MonoFrame>, outRate: number): AsyncGenerator<Int16Array> {
  const ready: Int16Array[] = [];
  const resampler = new Resampler(first.sampleRate, 1, (pcm, count) => ready.push(pcm.slice(0, count)), outRate);
  let frame: MonoFrame = first;
  try {
    for (;;) {
      resampler.pushFloat32(frame.samples, frame.samples.length);
      yield* ready.splice(0);
      const next = await rest.next();
      if (next.done) break;
      frame = next.value;
    }
    resampler.flush();
    yield* ready.splice(0);
  } finally {
    await rest.return?.();
  }
}
