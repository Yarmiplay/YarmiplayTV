import { audioStreamToConfig, laFrameToAudioData, packetToEncodedAudioChunk } from "libavjs-webcodecs-bridge";
import workletUrl from "./audio-worklet.ts?worker&url";
import type { StreamInfo, TimedPacket } from "./demuxer";
import type { LibAV } from "./libav";

/** Stereo sound at [sampleRate], starting at file time [time]. */
export interface PcmChunk {
  time: number;
  sampleRate: number;
  left: Float32Array;
  right: Float32Array;
}

/**
 * The sound card side: an AudioContext with the player's worklet, which plays what it's sent and keeps the clock.
 * Made once per file at the audio track's sample rate, so nothing is resampled here (the browser does it once).
 */
export class AudioOutput {
  readonly context: AudioContext;
  private node!: AudioWorkletNode;
  private lastClock = { mediaTime: 0, contextTime: 0, playing: false, buffered: 0, rate: 1 };
  private rate = 1;

  private constructor(sampleRate: number) {
    this.context = new AudioContext({ sampleRate, latencyHint: "playback" });
  }

  static async create(sampleRate: number): Promise<AudioOutput> {
    const output = new AudioOutput(sampleRate);
    await output.context.audioWorklet.addModule(workletUrl);
    output.node = new AudioWorkletNode(output.context, "player-audio", { outputChannelCount: [2] });
    output.node.port.onmessage = (event) => {
      if (event.data.type === "clock") output.lastClock = event.data;
    };
    output.node.connect(output.context.destination);
    await output.context.suspend();
    return output;
  }

  get sampleRate() {
    return this.context.sampleRate;
  }

  /** Seconds of sound queued and not yet played. */
  get buffered(): number {
    return this.lastClock.buffered;
  }

  push(chunk: PcmChunk) {
    this.node.port.postMessage({ type: "chunk", time: chunk.time, left: chunk.left, right: chunk.right }, [
      chunk.left.buffer,
      chunk.right.buffer,
    ]);
  }

  /** Drops everything queued; sound starts again at file time [startAt]. */
  flush(startAt: number) {
    this.lastClock = { mediaTime: startAt, contextTime: this.context.currentTime, playing: false, buffered: 0, rate: this.rate };
    this.node.port.postMessage({ type: "flush", startAt });
  }

  setRate(rate: number) {
    this.rate = rate;
    this.node.port.postMessage({ type: "rate", rate });
  }

  setVolume(volume: number) {
    this.node.port.postMessage({ type: "volume", volume });
  }

  async play() {
    await this.context.resume();
  }

  async pause() {
    await this.context.suspend();
  }

  /** The file time being heard now: the worklet's last report, run on to now and less the output latency. */
  time(): number {
    const c = this.lastClock;
    if (!c.playing || this.context.state !== "running") return c.mediaTime;
    const since = Math.max(0, this.context.currentTime - c.contextTime);
    const latency = (this.context.outputLatency || 0) + (this.context.baseLatency || 0);
    return Math.max(0, c.mediaTime + (since - latency) * c.rate);
  }

  close() {
    this.node?.disconnect();
    void this.context.close();
  }
}

/** Mixes [planes] (FFmpeg's channel order: FL FR FC LFE BL BR SL SR) down to stereo. */
export function downmix(planes: Float32Array[]): [Float32Array, Float32Array] {
  if (planes.length === 1) return [planes[0], planes[0].slice()];
  if (planes.length === 2) return [planes[0], planes[1]];
  const n = planes[0].length;
  const left = new Float32Array(n);
  const right = new Float32Array(n);
  const [fl, fr, fc, , bl, br, sl, sr] = planes;
  const c = 0.7071;
  // Scaled so a full-scale signal in every channel stays below clipping, as FFmpeg's default stereo downmix does.
  const gain = 1 / (1 + c + (bl ? c : 0) + (sl ? c : 0));
  for (let i = 0; i < n; i++) {
    let l = fl[i] + (fc ? c * fc[i] : 0);
    let r = fr[i] + (fc ? c * fc[i] : 0);
    if (bl) {
      l += c * bl[i];
      r += c * br[i];
    }
    if (sl) {
      l += c * sl[i];
      r += c * sr[i];
    }
    left[i] = l * gain;
    right[i] = r * gain;
  }
  return [left, right];
}

/** One AudioData as stereo float samples. */
function toPcm(data: AudioData, timeOffset = 0): PcmChunk {
  const planes: Float32Array[] = [];
  for (let c = 0; c < data.numberOfChannels; c++) {
    const plane = new Float32Array(data.numberOfFrames);
    data.copyTo(plane, { planeIndex: c, format: "f32-planar" });
    planes.push(plane);
  }
  const [left, right] = downmix(planes);
  const chunk = { time: data.timestamp / 1e6 + timeOffset, sampleRate: data.sampleRate, left, right };
  data.close();
  return chunk;
}

/** Decodes one audio stream to stereo PCM, with WebCodecs when the browser can, else with FFmpeg (DTS, AC-3...). */
export interface AudioDecoding {
  readonly hardware: boolean;
  /** Sample rate of the decoded sound. */
  readonly sampleRate: number;
  /** Decodes [packets], in order (one call per batch: FFmpeg's decoder is a round trip to its worker). */
  decode(packets: TimedPacket[]): Promise<void>;
  /** Forgets everything in flight, as before a seek. */
  reset(): Promise<void>;
  /** Packets waiting to be decoded. */
  readonly queued: number;
  close(): Promise<void>;
}

/** Opus needs its identification header; without it Chrome reports the codec and then never plays it. */
async function withOpusHeader(libav: LibAV, stream: StreamInfo, config: AudioDecoderConfig): Promise<AudioDecoderConfig> {
  if (config.codec !== "opus" || config.description) return config;
  const size = await libav.AVCodecParameters_extradata_size(stream.raw.codecpar).catch(() => 0);
  if (size <= 0) return config;
  const description = await libav.copyout_u8(await libav.AVCodecParameters_extradata(stream.raw.codecpar), size);
  return { ...config, description };
}

export async function openAudioDecoding(
  libav: LibAV,
  stream: StreamInfo,
  output: (chunk: PcmChunk) => void,
  onError: (error: Error) => void,
): Promise<AudioDecoding> {
  const config = await audioStreamToConfig(libav, stream.raw).catch(() => null);
  if (config && typeof AudioDecoder !== "undefined") {
    const ready = await withOpusHeader(libav, stream, config);
    const support = await AudioDecoder.isConfigSupported(ready).catch(() => null);
    if (support?.supported) return new WebCodecsAudio(stream, support.config ?? ready, output, onError);
  }
  return openSoftwareAudio(libav, stream, output);
}

/** FFmpeg's decoder, for codecs the browser has no WebCodecs decoder for, and when that decoder never plays. */
export function openSoftwareAudio(
  libav: LibAV,
  stream: StreamInfo,
  output: (chunk: PcmChunk) => void,
): Promise<AudioDecoding> {
  return FfmpegAudio.open(libav, stream, output);
}

/** The chunk must be exactly the packet. A view into a larger buffer would hand the decoder the bytes around it. */
function tight<T extends { data: Uint8Array }>(packet: T): T {
  const data = packet.data;
  if (data.byteOffset === 0 && data.byteLength === data.buffer.byteLength) return packet;
  return { ...packet, data: new Uint8Array(data) };
}

class WebCodecsAudio implements AudioDecoding {
  readonly hardware = true;
  private decoder: AudioDecoder;

  constructor(
    private readonly stream: StreamInfo,
    private readonly config: AudioDecoderConfig,
    private readonly output: (chunk: PcmChunk) => void,
    private readonly onError: (error: Error) => void,
  ) {
    this.decoder = this.make();
  }

  private make() {
    const decoder = new AudioDecoder({
      output: (data) => {
        try {
          this.output(toPcm(data));
        } catch (e) {
          data.close();
          this.onError(e as Error);
        }
      },
      error: (e) => this.onError(e),
    });
    decoder.configure(this.config);
    return decoder;
  }

  get sampleRate() {
    return this.config.sampleRate;
  }

  get queued() {
    return this.decoder.decodeQueueSize;
  }

  async decode(packets: TimedPacket[]) {
    if (this.decoder.state === "closed") this.decoder = this.make();
    try {
      for (const p of packets) this.decoder.decode(packetToEncodedAudioChunk(tight(p.packet), this.stream.timeBase));
    } catch (e) {
      this.onError(e as Error);
    }
  }

  async reset() {
    if (this.decoder.state !== "closed") this.decoder.reset();
    this.decoder.configure(this.config);
  }

  async close() {
    if (this.decoder.state !== "closed") this.decoder.close();
  }
}

class FfmpegAudio implements AudioDecoding {
  readonly hardware = false;
  queued = 0;

  private constructor(
    private readonly libav: LibAV,
    private readonly stream: StreamInfo,
    private readonly output: (chunk: PcmChunk) => void,
    private ctx: number,
    private pkt: number,
    private frame: number,
    readonly sampleRate: number,
  ) {}

  static async open(libav: LibAV, stream: StreamInfo, output: (chunk: PcmChunk) => void): Promise<FfmpegAudio> {
    const [, ctx, pkt, frame] = await libav.ff_init_decoder(stream.raw.codec_id, {
      codecpar: stream.raw.codecpar,
      time_base: stream.timeBase,
    });
    const sampleRate = await libav.AVCodecParameters_sample_rate(stream.raw.codecpar);
    return new FfmpegAudio(libav, stream, output, ctx, pkt, frame, sampleRate || 48000);
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
      for (const frame of frames) {
        this.output(toPcm(laFrameToAudioData(frame, { timeBase: this.stream.timeBase })));
      }
    } finally {
      this.queued -= packets.length;
    }
  }

  async reset() {
    await this.libav.avcodec_flush_buffers(this.ctx);
  }

  async close() {
    await this.libav.ff_free_decoder(this.ctx, this.pkt, this.frame);
  }
}
