import { FileSource } from "./bytes";
import { Demuxer, type StreamInfo } from "./demuxer";
import { createLibAV, type LibAV } from "./libav";
import { openVideoDecoding, type VideoDecoding } from "./video";

/**
 * Pictures for the seek bar's preview (SeekFrames.kt, 1.8.9): the keyframe at or a little before a time, from a file
 * on this device, decoded by its own FFmpeg and decoder so the playing video is never disturbed. Streams get none,
 * as every picture would be another download.
 */
export class SeekFrames {
  private busy: Promise<unknown> = Promise.resolve();
  private latest = 0;
  private closed = false;

  private constructor(
    private readonly libav: LibAV,
    private readonly demuxer: Demuxer,
    private readonly stream: StreamInfo,
    private readonly decoding: VideoDecoding,
    private readonly frames: VideoFrame[],
  ) {}

  static async open(file: Blob): Promise<SeekFrames | null> {
    const libav = await createLibAV();
    try {
      const demuxer = await Demuxer.open(libav, new FileSource(file));
      const stream = demuxer.streams.find((s) => s.kind === "video" && !["mjpeg", "png", "bmp"].includes(s.codec));
      if (!stream) {
        await demuxer.close();
        libav.terminate();
        return null;
      }
      const frames: VideoFrame[] = [];
      const decoding = await openVideoDecoding(libav, stream, (f) => frames.push(f), () => {});
      return new SeekFrames(libav, demuxer, stream, decoding, frames);
    } catch {
      libav.terminate();
      return null;
    }
  }

  /**
   * The picture at [seconds], [width] pixels wide; null when there's none or a newer request came first (only the
   * latest one is worked on, so dragging along the bar stays quick).
   */
  frame(seconds: number, width: number): Promise<ImageBitmap | null> {
    const ticket = ++this.latest;
    const job = this.busy.then(async () => {
      if (ticket !== this.latest || this.closed) return null;
      try {
        await this.decoding.reset();
        await this.demuxer.seek(seconds);
        for (let reads = 0; reads < 40; reads++) {
          const { packets, eof } = await this.demuxer.read(256 * 1024);
          const key = packets.find((p) => p.stream === this.stream.index && p.key);
          if (key) {
            await this.decoding.decode([key]);
            await this.decoding.flush();
            break;
          }
          if (eof) break;
        }
        const frame = this.frames.shift();
        this.frames.splice(0).forEach((f) => f.close());
        if (!frame) return null;
        const height = Math.round((width * frame.displayHeight) / frame.displayWidth);
        const bitmap = await createImageBitmap(frame, { resizeWidth: width, resizeHeight: height, resizeQuality: "medium" });
        frame.close();
        return bitmap;
      } catch {
        return null;
      }
    });
    this.busy = job.catch(() => null);
    return job;
  }

  /** Done with previews for now: lets go of the decoder and FFmpeg. */
  async close() {
    this.closed = true;
    await this.busy;
    this.frames.splice(0).forEach((f) => f.close());
    await this.decoding.close().catch(() => {});
    await this.demuxer.close().catch(() => {});
    this.libav.terminate();
  }
}
