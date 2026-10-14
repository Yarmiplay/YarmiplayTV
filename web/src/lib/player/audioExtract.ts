import { laFrameToAudioData } from "libavjs-webcodecs-bridge";
import type { ByteSource } from "./bytes";
import { Demuxer, type StreamInfo } from "./demuxer";
import { createLibAV, type LibAV } from "./libav";

/**
 * A stretch of one file's sound as mono samples, for listening for openings and endings (the apps' AudioDecoder for
 * the detector): its own FFmpeg reads and decodes only the audio, from [from] seconds for [duration] seconds. Every
 * codec goes through FFmpeg here (no WebCodecs), so it also works in a worker and never touches the player.
 */
export interface MonoChunk {
  /** File time of the first sample. */
  time: number;
  sampleRate: number;
  samples: Float32Array;
}

const languageMatches = (language: string | null, wanted: string[]) =>
  language !== null && wanted.some((w) => w.trim().toLowerCase() === language.toLowerCase());

export async function* decodeMono(
  open: () => Promise<ByteSource>,
  from: number,
  duration: number,
  opts: { languages?: string[]; signal?: AbortSignal } = {},
): AsyncGenerator<MonoChunk> {
  const libav: LibAV = await createLibAV();
  let demuxer: Demuxer | null = null;
  let decoder: [number, number, number, number] | null = null;
  try {
    demuxer = await Demuxer.open(libav, await open());
    const audios = demuxer.streams.filter((s) => s.kind === "audio");
    const stream: StreamInfo | undefined =
      audios.find((s) => languageMatches(s.language, opts.languages ?? [])) ?? audios.find((s) => s.isDefault) ?? audios[0];
    if (!stream) return;
    decoder = await libav.ff_init_decoder(stream.raw.codec_id, { codecpar: stream.raw.codecpar, time_base: stream.timeBase });
    const [, ctx, pkt, frame] = decoder;
    const end = from + duration;
    await demuxer.seek(Math.max(0, demuxer.startTime + from - 1));
    let eof = false;
    while (!eof) {
      if (opts.signal?.aborted) return;
      const read = await demuxer.read(512 * 1024);
      eof = read.eof;
      const packets = read.packets.filter((p) => p.stream === stream.index).map((p) => p.packet);
      if (!packets.length && !eof) continue;
      const frames = await libav.ff_decode_multi(ctx, pkt, frame, packets, { fin: eof, ignoreErrors: true });
      let past = false;
      for (const f of frames) {
        const data = laFrameToAudioData(f, { timeBase: stream.timeBase });
        const time = data.timestamp / 1e6 - demuxer.startTime;
        const n = data.numberOfFrames;
        const mono = new Float32Array(n);
        const plane = new Float32Array(n);
        for (let c = 0; c < data.numberOfChannels; c++) {
          data.copyTo(plane, { planeIndex: c, format: "f32-planar" });
          for (let i = 0; i < n; i++) mono[i] += plane[i] / data.numberOfChannels;
        }
        const rate = data.sampleRate;
        data.close();
        if (time + n / rate <= from) continue;
        if (time >= end) {
          past = true;
          continue;
        }
        // Trimmed to [from, end).
        const start = Math.max(0, Math.round((from - time) * rate));
        const stop = Math.min(n, Math.round((end - time) * rate));
        if (stop > start) yield { time: time + start / rate, sampleRate: rate, samples: mono.subarray(start, stop) };
      }
      if (past) return;
    }
  } finally {
    if (decoder) await libav.ff_free_decoder(decoder[1], decoder[2], decoder[3]).catch(() => {});
    await demuxer?.close().catch(() => {});
    libav.terminate();
  }
}

/** How long a file is, read by its own FFmpeg (for listening to episodes whose length no server gave). */
export async function mediaDuration(source: ByteSource): Promise<number | null> {
  const libav = await createLibAV();
  try {
    // The caller reads the same source afterwards: closing the demuxer mustn't close it.
    const borrowed: ByteSource = { size: source.size, name: source.name, read: (p, l) => source.read(p, l), close() {} };
    const demuxer = await Demuxer.open(libav, borrowed);
    const duration = demuxer.duration;
    await demuxer.close();
    return duration > 0 ? duration : null;
  } catch {
    return null;
  } finally {
    libav.terminate();
  }
}
