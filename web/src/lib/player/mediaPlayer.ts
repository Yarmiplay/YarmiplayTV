import type { PlayerAdapter } from "../syncplay/models";
import { AudioOutput, openAudioDecoding, type AudioDecoding, type PcmChunk } from "./audio";
import type { ByteSource } from "./bytes";
import { Demuxer, type Chapter, type StreamInfo, type TimedPacket } from "./demuxer";
import { createLibAV, type LibAV } from "./libav";
import { Renderer } from "./renderer";
import { PGS, PgsLayer, SubtitleLayer, TEXT_SUBTITLES } from "./subtitles";
import { openVideoDecoding, type VideoDecoding } from "./video";

export interface Track {
  /** The stream's index in the file. */
  id: number;
  kind: "audio" | "subtitle";
  label: string;
  language: string | null;
  codec: string;
  isDefault: boolean;
  /** Whether the player can use it (PGS subtitles aren't drawn yet). */
  playable: boolean;
}

export interface PlayerState {
  loaded: boolean;
  name: string;
  duration: number;
  position: number;
  paused: boolean;
  buffering: boolean;
  ended: boolean;
  speed: number;
  volume: number;
  muted: boolean;
  error: string | null;
  video: { codec: string; decoder: "hardware" | "software"; width: number; height: number } | null;
  audioDecoder: "hardware" | "software" | null;
  audioTracks: Track[];
  subtitleTracks: Track[];
  audioTrack: number | null;
  subtitleTrack: number | null;
  chapters: Chapter[];
}

export interface LoadOptions {
  /** File time to start at. */
  start?: number;
  /** Audio and subtitle languages to pick first, as Settings > Playback lists them ("jpn,ja", "eng,en"). */
  audioLanguages?: string[];
  subtitleLanguages?: string[];
}

const initialState = (): PlayerState => ({
  loaded: false,
  name: "",
  duration: 0,
  position: 0,
  paused: true,
  buffering: false,
  ended: false,
  speed: 1,
  volume: 1,
  muted: false,
  error: null,
  video: null,
  audioDecoder: null,
  audioTracks: [],
  subtitleTracks: [],
  audioTrack: null,
  subtitleTrack: null,
  chapters: [],
});

/** Video frames decoded ahead of the clock, at most. */
const MAX_FRAMES = 8;
/** Seconds of sound decoded ahead, at least and at most. */
const AUDIO_LOW = 1.0;
const AUDIO_HIGH = 3.0;
/** Packets waiting in a decoder, at most, before the demuxer stops reading. */
const MAX_QUEUED = 40;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const languageMatches = (language: string | null, wanted: string[]) =>
  language !== null && wanted.some((w) => w.trim().toLowerCase() === language.toLowerCase());

function trackLabel(s: StreamInfo): string {
  const parts = [s.title, s.language?.toUpperCase(), s.codec.toUpperCase()].filter(Boolean);
  return parts.join(" · ") || `Track ${s.index}`;
}

/**
 * The browser player: plays a file from a [ByteSource] onto a canvas, with its sound through the audio worklet, its
 * subtitles through libass, and the controls the sync engine needs ([PlayerAdapter]). Reads reflect commands at once,
 * even while a seek or a play is still being carried out.
 */
export class MediaPlayer implements PlayerAdapter {
  private readonly renderer: Renderer;
  private libav: LibAV | null = null;
  private demuxer: Demuxer | null = null;
  private videoStream: StreamInfo | null = null;
  private video: VideoDecoding | null = null;
  private audioStream: StreamInfo | null = null;
  private audio: AudioDecoding | null = null;
  private output: AudioOutput | null = null;
  private subtitles: SubtitleLayer | PgsLayer | null = null;

  private readonly frames: VideoFrame[] = [];
  private _source: ByteSource | null = null;
  private generation = 0;
  private eof = false;
  /** File time of the file's start; positions shown count from here (MPEG-TS files start a second or so in). */
  private origin = 0;
  /** Where a seek is going, in file time; null when none is. */
  private seekTarget: number | null = null;
  private seekPending = false;
  /** Frames and sound before this file time are dropped (the moment a seek went to). */
  private dropBefore = 0;

  // The clock when there's no sound: file time [wallBase] at performance time [wallStart].
  private wallBase = 0;
  private wallStart = performance.now();

  private state: PlayerState = initialState();
  private readonly listeners = new Set<(state: PlayerState) => void>();
  private lastEmit = 0;
  private frameRequest = 0;
  private closed = false;

  constructor(
    canvas: HTMLCanvasElement,
    private readonly subtitleCanvas: HTMLCanvasElement,
    /** For PGS (picture) subtitles; the text subtitles' canvas belongs to libass's worker. */
    private readonly pictureSubtitleCanvas: HTMLCanvasElement | null = null,
  ) {
    this.renderer = new Renderer(canvas);
    this.frameRequest = requestAnimationFrame(this.tick);
  }

  // --- PlayerAdapter ------------------------------------------------------------

  get isFileLoaded() {
    return this.state.loaded;
  }

  get duration() {
    return this.state.duration;
  }

  get position() {
    return (this.seekTarget ?? this.clock()) - this.origin;
  }

  get isPaused() {
    return this.state.paused;
  }

  setPaused(paused: boolean) {
    if (paused === this.state.paused) return;
    this.wallBase = this.clock();
    this.wallStart = performance.now();
    this.update({ paused, ended: paused ? this.state.ended : false });
    if (this.output) void (paused ? this.output.pause() : this.output.play());
  }

  seek(position: number) {
    if (!this.state.loaded) return;
    const shown = Math.max(0, Math.min(position, this.state.duration || position));
    this.seekTarget = this.origin + shown;
    this.seekPending = true;
    this.update({ position: shown, ended: false });
  }

  setSpeed(speed: number) {
    this.wallBase = this.clock();
    this.wallStart = performance.now();
    this.output?.setRate(speed);
    this.update({ speed });
  }

  // --- The rest of the player's controls ------------------------------------------

  subscribe(listener: (state: PlayerState) => void): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => this.listeners.delete(listener);
  }

  get snapshot(): PlayerState {
    return this.state;
  }

  /** Where the file playing now is read from (a file on this device gets seek-bar pictures). */
  get source(): ByteSource | null {
    return this._source;
  }

  setVolume(volume: number, muted = this.state.muted) {
    this.output?.setVolume(muted ? 0 : volume);
    this.update({ volume, muted });
  }

  async load(source: ByteSource, opts: LoadOptions = {}): Promise<void> {
    const generation = ++this.generation;
    await this.teardown();
    this.state = { ...initialState(), name: source.name, volume: this.state.volume, muted: this.state.muted, speed: this.state.speed };
    this.emit();
    try {
      this.libav ??= await createLibAV();
      const libav = this.libav;
      const demuxer = await Demuxer.open(libav, source);
      if (generation !== this.generation) return void demuxer.close();
      this.demuxer = demuxer;
      this._source = source;

      const videos = demuxer.streams.filter((s) => s.kind === "video" && !["mjpeg", "png", "bmp"].includes(s.codec));
      const audios = demuxer.streams.filter((s) => s.kind === "audio");
      const subtitles = demuxer.streams.filter((s) => s.kind === "subtitle");
      this.videoStream = videos[0] ?? null;
      this.audioStream =
        audios.find((s) => languageMatches(s.language, opts.audioLanguages ?? [])) ?? audios.find((s) => s.isDefault) ?? audios[0] ?? null;
      const textSubs = subtitles.filter((s) => this.canDraw(s));
      const subtitle =
        textSubs.find((s) => s.isForced) ??
        textSubs.find((s) => languageMatches(s.language, opts.subtitleLanguages ?? [])) ??
        textSubs.find((s) => s.isDefault) ??
        textSubs[0] ??
        null;

      if (this.videoStream) {
        this.video = await openVideoDecoding(libav, this.videoStream, this.onFrame, (e) => this.fail(e));
      }
      if (this.audioStream) await this.openAudio(this.audioStream);
      if (subtitle) await this.openSubtitles(subtitle);

      const toTrack = (s: StreamInfo, kind: Track["kind"]): Track => ({
        id: s.index,
        kind,
        label: trackLabel(s),
        language: s.language,
        codec: s.codec,
        isDefault: s.isDefault,
        playable: kind === "audio" || this.canDraw(s),
      });
      const width = this.videoStream ? await libav.AVCodecParameters_width(this.videoStream.raw.codecpar) : 0;
      const height = this.videoStream ? await libav.AVCodecParameters_height(this.videoStream.raw.codecpar) : 0;
      if (generation !== this.generation) return;

      this.eof = false;
      this.origin = demuxer.startTime;
      this.wallBase = this.origin;
      this.wallStart = performance.now();
      this.update({
        loaded: true,
        duration: demuxer.duration,
        position: 0,
        paused: true,
        video: this.video ? { codec: this.video.codec, decoder: this.video.kind, width, height } : null,
        audioDecoder: this.audioKind(),
        audioTracks: audios.map((s) => toTrack(s, "audio")),
        subtitleTracks: subtitles.map((s) => toTrack(s, "subtitle")),
        audioTrack: this.audioStream?.index ?? null,
        subtitleTrack: subtitle?.index ?? null,
        chapters: demuxer.chapters.map((c) => ({ ...c, start: c.start - this.origin, end: c.end - this.origin })),
      });
      if (opts.start && opts.start > 0) this.seek(opts.start);
      void this.pump(generation);
    } catch (e) {
      if (generation === this.generation) this.fail(e as Error);
      throw e;
    }
  }

  async selectAudio(id: number | null) {
    const stream = this.demuxer?.streams.find((s) => s.index === id && s.kind === "audio") ?? null;
    await this.audio?.close().catch(() => {});
    this.audio = null;
    this.output?.close();
    this.output = null;
    this.audioStream = stream;
    if (stream) await this.openAudio(stream);
    this.update({ audioTrack: stream?.index ?? null, audioDecoder: this.audioKind() });
    this.seek(this.position);
  }

  async selectSubtitle(id: number | null) {
    await this.subtitles?.destroy();
    this.subtitles = null;
    const stream = this.demuxer?.streams.find((s) => s.index === id && s.kind === "subtitle") ?? null;
    if (stream && this.canDraw(stream)) await this.openSubtitles(stream);
    this.update({ subtitleTrack: this.subtitles ? stream!.index : null });
    // Back a little, so a line that started before now is read again.
    if (this.subtitles) this.seek(this.position);
  }

  /** The frame on screen as a PNG; with [withSubtitles], the subtitles drawn over it. */
  async screenshot(withSubtitles: boolean): Promise<Blob | null> {
    const plain = await this.renderer.screenshot();
    if (!plain || !withSubtitles || !this.subtitles) return plain;
    const image = await createImageBitmap(plain);
    const canvas = new OffscreenCanvas(image.width, image.height);
    const ctx = canvas.getContext("2d")!;
    ctx.drawImage(image, 0, 0);
    const [x, y, w, h] = this.renderer.fit(image.width, image.height);
    const scaleX = image.width / w;
    const scaleY = image.height / h;
    const overlay = this.subtitles instanceof PgsLayer ? this.pictureSubtitleCanvas! : this.subtitleCanvas;
    ctx.drawImage(overlay, x, y, w, h, 0, 0, w * scaleX, h * scaleY);
    return canvas.convertToBlob({ type: "image/png" });
  }

  async close() {
    this.closed = true;
    this.generation++;
    cancelAnimationFrame(this.frameRequest);
    await this.teardown();
    this.libav?.terminate();
    this.libav = null;
    this.listeners.clear();
  }

  // --- Inside ---------------------------------------------------------------------

  private audioKind(): "hardware" | "software" | null {
    const audio = this.audio as AudioDecoding | null;
    return audio ? (audio.hardware ? "hardware" : "software") : null;
  }

  private async openAudio(stream: StreamInfo) {
    const libav = this.libav!;
    const sampleRate = (await libav.AVCodecParameters_sample_rate(stream.raw.codecpar)) || 48000;
    this.output = await AudioOutput.create(sampleRate);
    this.output.setRate(this.state.speed);
    this.output.setVolume(this.state.muted ? 0 : this.state.volume);
    this.audio = await openAudioDecoding(libav, stream, this.onPcm, (e) => this.fail(e));
    if (!this.state.paused) await this.output.play();
  }

  /** Whether [stream]'s subtitles can be drawn: text ones always, pictures when there's a canvas for them. */
  private canDraw(stream: StreamInfo): boolean {
    return TEXT_SUBTITLES.has(stream.codec) || (stream.codec === PGS && this.pictureSubtitleCanvas !== null);
  }

  private async openSubtitles(stream: StreamInfo) {
    if (stream.codec === PGS) {
      this.subtitles = new PgsLayer(this.pictureSubtitleCanvas!, stream);
      return;
    }
    const demuxer = this.demuxer!;
    const [header, fonts] = await Promise.all([demuxer.extradata(stream), demuxer.attachments()]);
    this.subtitles = new SubtitleLayer(this.subtitleCanvas, stream, header, fonts);
    await this.subtitles.ready().catch(() => {});
  }

  private async teardown() {
    this.frames.splice(0).forEach((f) => f.close());
    this.seekTarget = null;
    this.seekPending = false;
    const video = this.video;
    const audio = this.audio;
    const demuxer = this.demuxer;
    this.video = this.audio = null;
    this.demuxer = null;
    this.output?.close();
    this.output = null;
    await this.subtitles?.destroy();
    this.subtitles = null;
    await video?.close().catch(() => {});
    await audio?.close().catch(() => {});
    await demuxer?.close().catch(() => {});
    this.renderer.clear();
  }

  /** The file time playing now. */
  private clock(): number {
    if (this.output && this.audioStream) return this.output.time();
    if (this.state.paused || this.state.buffering) return this.wallBase;
    return this.wallBase + ((performance.now() - this.wallStart) / 1000) * this.state.speed;
  }

  private readonly onFrame = (frame: VideoFrame) => {
    const time = frame.timestamp / 1e6;
    if (time < this.dropBefore - 0.001 || this.closed) {
      frame.close();
      return;
    }
    // Decoders give frames in presentation order; keep the queue sorted anyway.
    let i = this.frames.length;
    while (i > 0 && this.frames[i - 1].timestamp > frame.timestamp) i--;
    this.frames.splice(i, 0, frame);
    if (this.seekTarget !== null && !this.seekPending) this.finishSeek();
  };

  private readonly onPcm = (chunk: PcmChunk) => {
    if (!this.output || this.closed) return;
    const end = chunk.time + chunk.left.length / chunk.sampleRate;
    if (end < this.dropBefore) return;
    this.output.push(chunk);
    if (this.seekTarget !== null && !this.seekPending && !this.videoStream) this.finishSeek();
  };

  private finishSeek() {
    this.wallBase = this.seekTarget ?? this.wallBase;
    this.wallStart = performance.now();
    this.seekTarget = null;
    this.update({ buffering: false });
  }

  /** Reads the file ahead of the clock and feeds the decoders, until the file or the generation changes. */
  private async pump(generation: number) {
    while (generation === this.generation && !this.closed) {
      try {
        if (this.seekPending) {
          await this.doSeek();
          continue;
        }
        if (this.eof || !this.demuxer || this.state.error) {
          await sleep(50);
          continue;
        }
        const audioAhead = this.output ? this.output.buffered : Infinity;
        const videoWants = !!this.video && this.frames.length < MAX_FRAMES && this.video.queued < MAX_QUEUED / 2;
        const audioWants = !!this.audio && audioAhead < AUDIO_HIGH && this.audio.queued < MAX_QUEUED;
        const audioStarved = !!this.audio && audioAhead < AUDIO_LOW;
        // Video decoders hold a few frames each; past this many waiting packets, reading waits whatever the sound needs.
        const videoBlocked = !!this.video && this.video.queued >= MAX_QUEUED;
        if (videoBlocked || !(videoWants || audioStarved || (audioWants && !this.video))) {
          await sleep(10);
          continue;
        }
        const { packets, eof } = await this.demuxer.read(512 * 1024);
        if (generation !== this.generation || this.seekPending) continue;
        await this.route(packets);
        if (eof) {
          this.eof = true;
          await this.video?.flush();
        }
      } catch (e) {
        if (generation === this.generation) this.fail(e as Error);
        return;
      }
    }
  }

  /** Hands a batch of packets to their decoders: one call each, as FFmpeg's are round trips to its worker. */
  private async route(packets: TimedPacket[]) {
    const video: TimedPacket[] = [];
    const audio: TimedPacket[] = [];
    for (const packet of packets) {
      if (packet.stream === this.videoStream?.index) video.push(packet);
      else if (packet.stream === this.audioStream?.index) audio.push(packet);
      else if (packet.stream === this.subtitles?.stream.index) this.subtitles.add(packet);
    }
    await Promise.all([
      video.length ? this.video?.decode(video) : null,
      audio.length ? this.audio?.decode(audio) : null,
    ]);
  }

  private async doSeek() {
    this.seekPending = false;
    const target = this.seekTarget ?? 0;
    this.update({ buffering: true });
    this.frames.splice(0).forEach((f) => f.close());
    await this.video?.reset();
    await this.audio?.reset();
    this.output?.flush(target);
    this.dropBefore = target;
    this.eof = false;
    // Subtitle lines that began a little before the target are read again too.
    await this.demuxer?.seek(this.subtitles ? Math.max(0, target - 1) : target);
    if (!this.videoStream && !this.audioStream) this.finishSeek();
  }

  /** Each animation frame: shows the frame due now, the subtitles, and notices the end of the file. */
  private readonly tick = () => {
    if (this.closed) return;
    this.frameRequest = requestAnimationFrame(this.tick);
    if (!this.state.loaded) return;
    const now = this.clock();
    if (this.seekTarget === null) {
      let due: VideoFrame | null = null;
      while (this.frames.length && this.frames[0].timestamp / 1e6 <= now + 0.008) {
        due?.close();
        due = this.frames.shift()!;
      }
      if (due) this.renderer.draw(due);
      else if (this.renderer.resize()) this.renderer.paint();
    }
    const size = this.renderer.frameSize;
    if (this.subtitles instanceof PgsLayer) this.subtitles.render(now);
    else if (this.subtitles && size) this.subtitles.render(now, size[0], size[1]);

    // Without sound to wait for, the clock waits for the video.
    const starved = !this.state.paused && !this.eof && this.seekTarget === null && !!this.video && this.frames.length === 0 && (!this.audioStream || (this.output?.buffered ?? 0) === 0);
    if (starved !== this.state.buffering && this.seekTarget === null) {
      if (starved) this.wallBase = now;
      this.wallStart = performance.now();
      this.update({ buffering: starved });
    }

    const atEnd = this.eof && this.frames.length === 0 && (this.output?.buffered ?? 0) < 0.05;
    if (atEnd && !this.state.paused && !this.state.ended && now - this.origin >= this.state.duration - 0.25) {
      this.wallBase = this.origin + this.state.duration;
      this.update({ ended: true });
      this.setPaused(true);
    }
    if (performance.now() - this.lastEmit > 200) this.update({ position: (this.seekTarget ?? now) - this.origin });
  };

  private fail(error: Error) {
    console.error("Player:", error);
    this.update({ error: error.message || String(error), buffering: false });
  }

  private update(change: Partial<PlayerState>) {
    this.state = { ...this.state, ...change };
    this.emit();
  }

  private emit() {
    this.lastEmit = performance.now();
    for (const listener of this.listeners) listener(this.state);
  }
}
