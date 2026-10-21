import { laFrameToVideoFrame, packetToEncodedVideoChunk, videoStreamToConfig } from "libavjs-webcodecs-bridge";
import type { StreamInfo, TimedPacket } from "./demuxer";
import type { LibAV } from "./libav";

/** Decodes the video stream to VideoFrames: in hardware through WebCodecs when the browser can, else with FFmpeg. */
export interface VideoDecoding {
  /** "hardware" through WebCodecs, or "software" through FFmpeg in WebAssembly. */
  readonly kind: "hardware" | "software";
  readonly codec: string;
  /** Decodes [packets], in order. */
  decode(packets: TimedPacket[]): Promise<void>;
  /** Decodes what's still inside the decoder (at the end of the file). */
  flush(): Promise<void>;
  /** Forgets everything in flight, as before a seek; decoding starts again at a keyframe. */
  reset(): Promise<void>;
  /** Packets waiting to be decoded. */
  readonly queued: number;
  close(): Promise<void>;
}

export class UnsupportedVideoError extends Error {}

export async function openVideoDecoding(
  libav: LibAV,
  stream: StreamInfo,
  output: (frame: VideoFrame) => void,
  onError: (error: Error) => void,
): Promise<VideoDecoding> {
  const config = await videoStreamToConfig(libav, stream.raw).catch(() => null);
  if (config && typeof VideoDecoder !== "undefined") {
    const support = await VideoDecoder.isConfigSupported(config).catch(() => null);
    if (support?.supported) return new WebCodecsVideo(stream, support.config ?? config, output, onError);
  }
  try {
    return await openSoftwareVideo(libav, stream, output);
  } catch {
    throw new UnsupportedVideoError(
      stream.codec === "hevc"
        ? "This browser can't decode HEVC (H.265) video on this device"
        : `This browser can't play ${stream.codec} video`,
    );
  }
}

class WebCodecsVideo implements VideoDecoding {
  readonly kind = "hardware";
  private decoder: VideoDecoder;
  /** Delta frames are refused until a keyframe arrives, after a reset. */
  private needKey = true;

  constructor(
    private readonly stream: StreamInfo,
    private readonly config: VideoDecoderConfig,
    private readonly output: (frame: VideoFrame) => void,
    private readonly onError: (error: Error) => void,
  ) {
    this.decoder = this.make();
  }

  get codec() {
    return this.config.codec;
  }

  private make() {
    const decoder = new VideoDecoder({ output: this.output, error: (e) => this.onError(e) });
    decoder.configure(this.config);
    return decoder;
  }

  get queued() {
    return this.decoder.decodeQueueSize;
  }

  async decode(packets: TimedPacket[]) {
    if (this.decoder.state === "closed") this.decoder = this.make();
    try {
      for (const p of packets) {
        if (this.needKey && !p.key) continue;
        this.needKey = false;
        this.decoder.decode(packetToEncodedVideoChunk(p.packet, this.stream.timeBase));
      }
    } catch (e) {
      this.onError(e as Error);
    }
  }

  async flush() {
    if (this.decoder.state === "configured") await this.decoder.flush().catch(() => {});
  }

  async reset() {
    // An error closes the decoder; configure on a closed one throws and stops the file.
    if (this.decoder.state === "closed") this.decoder = this.make();
    else {
      this.decoder.reset();
      this.decoder.configure(this.config);
    }
    this.needKey = true;
  }

  async close() {
    if (this.decoder.state !== "closed") this.decoder.close();
  }
}

/** FFmpeg's decoder, for codecs the browser has no WebCodecs decoder for, and when that decoder reports an error. */
export function openSoftwareVideo(
  libav: LibAV,
  stream: StreamInfo,
  output: (frame: VideoFrame) => void,
): Promise<VideoDecoding> {
  return FfmpegVideo.open(libav, stream, output);
}

class FfmpegVideo implements VideoDecoding {
  readonly kind = "software";
  queued = 0;

  private constructor(
    private readonly libav: LibAV,
    private readonly stream: StreamInfo,
    private readonly output: (frame: VideoFrame) => void,
    private ctx: number,
    private pkt: number,
    private frame: number,
  ) {}

  get codec() {
    return this.stream.codec;
  }

  static async open(libav: LibAV, stream: StreamInfo, output: (frame: VideoFrame) => void): Promise<FfmpegVideo> {
    const [, ctx, pkt, frame] = await libav.ff_init_decoder(stream.raw.codec_id, {
      codecpar: stream.raw.codecpar,
      time_base: stream.timeBase,
    });
    return new FfmpegVideo(libav, stream, output, ctx, pkt, frame);
  }

  async decode(packets: TimedPacket[]) {
    this.queued += packets.length;
    try {
      const frames = await this.libav.ff_decode_multi(
        this.ctx,
        this.pkt,
        this.frame,
        packets.map((p) => p.packet),
        { ignoreErrors: true },
      );
      for (const frame of frames) this.output(laFrameToVideoFrame(frame, { timeBase: this.stream.timeBase, transfer: true }));
    } finally {
      this.queued -= packets.length;
    }
  }

  async flush() {
    const frames = await this.libav.ff_decode_multi(this.ctx, this.pkt, this.frame, [], { fin: true, ignoreErrors: true });
    for (const frame of frames) this.output(laFrameToVideoFrame(frame, { timeBase: this.stream.timeBase, transfer: true }));
  }

  async reset() {
    await this.libav.avcodec_flush_buffers(this.ctx);
  }

  async close() {
    await this.libav.ff_free_decoder(this.ctx, this.pkt, this.frame);
  }
}
