/**
 * The player's FFmpeg: libav.js built by libav/build.sh with the formats and codecs in libav/config.json, served from
 * public/libav/. Only the parts of its API the player uses are typed here.
 */

export const LIBAV_VERSION = "6.10.9.0";
export const LIBAV_VARIANT = "yarmiplay";

export interface LavPacket {
  data: Uint8Array;
  pts?: number;
  ptshi?: number;
  dts?: number;
  dtshi?: number;
  duration?: number;
  durationhi?: number;
  time_base_num?: number;
  time_base_den?: number;
  stream_index?: number;
  flags?: number;
}

export interface LavFrame {
  data: unknown;
  format: number;
  pts?: number;
  ptshi?: number;
  sample_rate?: number;
  channels?: number;
  nb_samples?: number;
  width?: number;
  height?: number;
  time_base_num?: number;
  time_base_den?: number;
}

export interface LavStream {
  ptr: number;
  index: number;
  codecpar: number;
  codec_type: number;
  codec_id: number;
  time_base_num: number;
  time_base_den: number;
  duration_time_base: number;
  duration: number;
  metadata?: Record<string, string>;
}

export interface LavChapter {
  start: number;
  end: number;
  metadata?: Record<string, string>;
}

/** A libav.js instance (it runs FFmpeg in its own worker; every call is asynchronous). */
export interface LibAV {
  AVMEDIA_TYPE_VIDEO: number;
  AVMEDIA_TYPE_AUDIO: number;
  AVMEDIA_TYPE_SUBTITLE: number;
  AVMEDIA_TYPE_ATTACHMENT: number;
  AVSEEK_FLAG_BACKWARD: number;
  AVERROR_EOF: number;
  EAGAIN: number;
  AV_PKT_FLAG_KEY: number;
  onblockread?: (filename: string, position: number, length: number) => void;
  mkblockreaderdev(name: string, size: number): Promise<void>;
  unlink(name: string): Promise<void>;
  ff_block_reader_dev_send(name: string, position: number, data: Uint8Array | null, opts?: { errorCode?: number }): Promise<void>;
  ff_init_demuxer_file(name: string): Promise<[number, LavStream[]]>;
  ff_get_demuxer_chapters(fmtCtx: number): Promise<LavChapter[]>;
  ff_read_frame_multi(fmtCtx: number, pkt: number, opts: { limit?: number; unify?: boolean }): Promise<[number, Record<number, LavPacket[]>]>;
  avformat_seek_file_max(fmtCtx: number, streamIndex: number, ts: number, tshi: number, flags: number): Promise<number>;
  av_seek_frame(fmtCtx: number, streamIndex: number, ts: number, tshi: number, flags: number): Promise<number>;
  avformat_close_input_js(fmtCtx: number): Promise<void>;
  AVFormatContext_start_time(fmtCtx: number): Promise<number>;
  AVFormatContext_start_timehi(fmtCtx: number): Promise<number>;
  av_packet_alloc(): Promise<number>;
  av_packet_free_js(pkt: number): Promise<void>;
  avcodec_get_name(id: number): Promise<string>;
  AVCodecParameters_extradata(codecpar: number): Promise<number>;
  AVCodecParameters_extradata_size(codecpar: number): Promise<number>;
  AVCodecParameters_width(codecpar: number): Promise<number>;
  AVCodecParameters_height(codecpar: number): Promise<number>;
  AVCodecParameters_sample_rate(codecpar: number): Promise<number>;
  AVCodecParameters_ch_layout_nb_channels(codecpar: number): Promise<number>;
  AVCodecParameters_color_trc(codecpar: number): Promise<number>;
  AVCodecParameters_color_primaries(codecpar: number): Promise<number>;
  copyout_u8(ptr: number, length: number): Promise<Uint8Array>;
  ff_init_decoder(
    name: string | number,
    opts?: { codecpar?: number; time_base?: [number, number] },
  ): Promise<[number, number, number, number]>;
  ff_decode_multi(ctx: number, pkt: number, frame: number, packets: LavPacket[], opts?: { fin?: boolean; ignoreErrors?: boolean }): Promise<LavFrame[]>;
  ff_free_decoder(ctx: number, pkt: number, frame: number): Promise<void>;
  avcodec_flush_buffers(ctx: number): Promise<void>;
  terminate(): void;
}

interface LibAVModule {
  LibAV(opts?: { base?: string; variant?: string; yesthreads?: boolean; nothreads?: boolean }): Promise<LibAV>;
}

let module: Promise<LibAVModule> | null = null;

/** The folder the libav.js files are served from. */
export const libavBase = (): string => new URL(`${import.meta.env.BASE_URL}libav`, location.href).href;

function loadModule(): Promise<LibAVModule> {
  module ??= import(/* @vite-ignore */ `${libavBase()}/libav-${LIBAV_VERSION}-${LIBAV_VARIANT}.mjs`).then(
    (m) => m.default as LibAVModule,
  );
  return module;
}

/** A new libav.js instance, in its own worker (single-threaded: see libav/build.sh). */
export async function createLibAV(): Promise<LibAV> {
  const mod = await loadModule();
  return mod.LibAV({ base: libavBase(), variant: LIBAV_VARIANT, nothreads: true });
}

/** Seconds of a 64-bit libav timestamp split in low and high words, in a time base. */
export function seconds(lo: number | undefined, hi: number | undefined, num: number, den: number): number | null {
  if (lo === undefined) return null;
  const high = hi ?? 0;
  // AV_NOPTS_VALUE is INT64_MIN: high word 0x80000000 with a zero low word.
  if (high === -0x80000000 && lo === 0) return null;
  const value = high * 0x100000000 + (lo >>> 0);
  return (value * num) / den;
}
