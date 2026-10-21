import type { ByteSource } from "./bytes";
import { seconds, type LavChapter, type LavPacket, type LavStream, type LibAV } from "./libav";

export type StreamKind = "video" | "audio" | "subtitle" | "attachment" | "other";

export interface StreamInfo {
  index: number;
  kind: StreamKind;
  /** FFmpeg's codec name: "h264", "hevc", "dts", "ass", "subrip", "hdmv_pgs_subtitle", "ttf"... */
  codec: string;
  language: string | null;
  title: string | null;
  isDefault: boolean;
  isForced: boolean;
  timeBase: [number, number];
  raw: LavStream;
}

export interface Chapter {
  start: number;
  end: number;
  title: string;
}

export interface Attachment {
  name: string;
  mimeType: string;
  data: Uint8Array;
}

/** A packet with its times in seconds. */
export interface TimedPacket {
  packet: LavPacket;
  stream: number;
  /** Presentation time, or the decode time when the file gives none. */
  time: number | null;
  duration: number | null;
  key: boolean;
}

/** AV_DISPOSITION_DEFAULT and AV_DISPOSITION_FORCED, as libav.js leaves dispositions out of its stream info. */
const isTrue = (value: string | undefined) => value === "1" || value?.toLowerCase() === "true";

let devices = 0;

/** Bytes sent to FFmpeg per read at least. */
const READ_AHEAD = 512 * 1024;

/**
 * One file opened with FFmpeg's demuxers: its streams, chapters and attached fonts, then its packets in file order.
 * FFmpeg reads the file through a block device whose reads this class answers from a [ByteSource].
 */
export class Demuxer {
  private constructor(
    private readonly libav: LibAV,
    private readonly device: string,
    private readonly source: ByteSource,
    private readonly fmtCtx: number,
    private readonly pkt: number,
    readonly streams: StreamInfo[],
    readonly chapters: Chapter[],
    readonly duration: number,
    /** File time of the start (MPEG-TS files start a second or so in); positions shown count from here. */
    readonly startTime: number,
  ) {}

  static async open(libav: LibAV, source: ByteSource): Promise<Demuxer> {
    const device = `input-${++devices}`;
    libav.onblockread = (name, position, length) => {
      if (name !== device) return;
      // More than FFmpeg asked for: it keeps the rest, and every read is a round trip to its worker.
      source.read(position, Math.max(length, READ_AHEAD)).then(
        (data) => libav.ff_block_reader_dev_send(name, position, data),
        () => libav.ff_block_reader_dev_send(name, position, null, { errorCode: -5 /* EIO */ }),
      );
    };
    await libav.mkblockreaderdev(device, source.size);
    const [fmtCtx, raw] = await libav.ff_init_demuxer_file(device);
    const streams: StreamInfo[] = [];
    for (const s of raw) streams.push(await describe(libav, s));
    const chapters = await readChapters(libav, fmtCtx);
    let duration = Math.max(0, ...raw.map((s) => (Number.isFinite(s.duration) ? s.duration : 0)));
    // WebM often leaves the stream duration at 0 and keeps the length on the file instead.
    if (!(duration > 0)) {
      const fromFile = await Promise.all([
        libav.AVFormatContext_duration(fmtCtx),
        libav.AVFormatContext_durationhi(fmtCtx),
      ])
        .then(([lo, hi]) => seconds(lo, hi, 1, 1_000_000))
        .catch(() => null);
      if (fromFile && fromFile > 0 && fromFile < 1e7) duration = fromFile;
    }
    const startTime =
      seconds(await libav.AVFormatContext_start_time(fmtCtx), await libav.AVFormatContext_start_timehi(fmtCtx), 1, 1_000_000) ?? 0;
    const pkt = await libav.av_packet_alloc();
    return new Demuxer(libav, device, source, fmtCtx, pkt, streams, chapters, duration, Math.max(0, startTime));
  }

  /** Fonts and other files attached to the file (MKV attachments), for styled subtitles. */
  async attachments(): Promise<Attachment[]> {
    const out: Attachment[] = [];
    for (const s of this.streams) {
      if (s.kind !== "attachment") continue;
      const size = await this.libav.AVCodecParameters_extradata_size(s.raw.codecpar);
      if (size <= 0) continue;
      const ptr = await this.libav.AVCodecParameters_extradata(s.raw.codecpar);
      out.push({
        name: s.raw.metadata?.filename ?? `attachment-${s.index}`,
        mimeType: s.raw.metadata?.mimetype ?? "",
        data: await this.libav.copyout_u8(ptr, size),
      });
    }
    return out;
  }

  /** The codec's own header for [stream]: for ASS subtitles, the script's styles. */
  async extradata(stream: StreamInfo): Promise<Uint8Array | null> {
    const size = await this.libav.AVCodecParameters_extradata_size(stream.raw.codecpar);
    if (size <= 0) return null;
    return this.libav.copyout_u8(await this.libav.AVCodecParameters_extradata(stream.raw.codecpar), size);
  }

  /** The next packets in file order, about [limitBytes] of them; [eof] once the file is done. */
  async read(limitBytes = 1 << 20): Promise<{ packets: TimedPacket[]; eof: boolean }> {
    const [result, byStream] = await this.libav.ff_read_frame_multi(this.fmtCtx, this.pkt, { limit: limitBytes, unify: true });
    const packets = (byStream[0] ?? []).map((packet) => this.timed(packet));
    const eof = result === this.libav.AVERROR_EOF;
    if (result < 0 && !eof && result !== -this.libav.EAGAIN) {
      throw new Error(`Couldn't read the file (error ${result})`);
    }
    return { packets, eof };
  }

  /** Moves reading to the keyframe at or before [time] seconds. */
  async seek(time: number): Promise<void> {
    // AV_TIME_BASE units, in 32-bit halves.
    const ts = Math.max(0, Math.round(time * 1_000_000));
    const lo = ts % 0x100000000 | 0;
    const hi = Math.floor(ts / 0x100000000);
    let ret = await this.libav.avformat_seek_file_max(this.fmtCtx, -1, lo, hi, 0);
    if (ret < 0) ret = await this.libav.av_seek_frame(this.fmtCtx, -1, lo, hi, this.libav.AVSEEK_FLAG_BACKWARD);
    if (ret < 0) throw new Error(`Couldn't seek (error ${ret})`);
  }

  async close(): Promise<void> {
    this.libav.onblockread = undefined;
    await this.libav.av_packet_free_js(this.pkt).catch(() => {});
    await this.libav.avformat_close_input_js(this.fmtCtx).catch(() => {});
    await this.libav.unlink(this.device).catch(() => {});
    this.source.close();
  }

  private timed(packet: LavPacket): TimedPacket {
    const stream = packet.stream_index ?? 0;
    const info = this.streams[stream];
    const [num, den] = packet.time_base_num ? [packet.time_base_num, packet.time_base_den!] : info.timeBase;
    const time = seconds(packet.pts, packet.ptshi, num, den) ?? seconds(packet.dts, packet.dtshi, num, den);
    const duration = packet.duration ? seconds(packet.duration, packet.durationhi, num, den) : null;
    return { packet, stream, time, duration, key: ((packet.flags ?? 0) & this.libav.AV_PKT_FLAG_KEY) !== 0 };
  }
}

/**
 * Chapters in seconds, from each AVChapter's own 64-bit times: libav.js's own seconds come from the low 32 bits only,
 * which Matroska's nanoseconds outgrow after 4.29 s.
 */
async function readChapters(libav: LibAV, fmtCtx: number): Promise<Chapter[]> {
  const raw = await libav.ff_get_demuxer_chapters(fmtCtx).catch((): LavChapter[] => []);
  const out: Chapter[] = [];
  for (const c of raw) {
    const [start, starthi, end, endhi, num, den] = await Promise.all([
      libav.AVChapter_start(c.ptr),
      libav.AVChapter_starthi(c.ptr),
      libav.AVChapter_end(c.ptr),
      libav.AVChapter_endhi(c.ptr),
      libav.AVChapter_time_base_num(c.ptr),
      libav.AVChapter_time_base_den(c.ptr),
    ]);
    out.push({
      start: seconds(start, starthi, num, den) ?? c.start,
      end: seconds(end, endhi, num, den) ?? c.end,
      title: c.metadata?.title ?? "",
    });
  }
  return out;
}

async function describe(libav: LibAV, s: LavStream): Promise<StreamInfo> {
  const kind: StreamKind =
    s.codec_type === libav.AVMEDIA_TYPE_VIDEO
      ? "video"
      : s.codec_type === libav.AVMEDIA_TYPE_AUDIO
        ? "audio"
        : s.codec_type === libav.AVMEDIA_TYPE_SUBTITLE
          ? "subtitle"
          : s.codec_type === libav.AVMEDIA_TYPE_ATTACHMENT
            ? "attachment"
            : "other";
  const meta = s.metadata ?? {};
  return {
    index: s.index,
    kind,
    codec: await libav.avcodec_get_name(s.codec_id).catch(() => "unknown"),
    language: meta.language && meta.language !== "und" ? meta.language : null,
    title: meta.title ?? null,
    isDefault: isTrue(meta.default) || (meta.DISPOSITION ?? "").includes("default"),
    isForced: isTrue(meta.forced) || (meta.DISPOSITION ?? "").includes("forced"),
    timeBase: [s.time_base_num, s.time_base_den],
    raw: s,
  };
}
