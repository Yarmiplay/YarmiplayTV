import JASSUB from "jassub";
import type { Attachment, StreamInfo, TimedPacket } from "./demuxer";

/** Text subtitle codecs drawn with libass; PGS (bitmap) subtitles come later. */
export const TEXT_SUBTITLES = new Set(["ass", "ssa", "subrip", "srt", "webvtt", "text", "mov_text"]);

/** A plain script for subtitles that bring no styles of their own (SRT, WebVTT): white text with a black outline. */
const PLAIN_HEADER = `[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Liberation Sans,64,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,1,2,60,60,50,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
`;

const utf8 = new TextDecoder();

/** SRT/WebVTT markup as ASS overrides: <i>, <b>, <u> and line breaks. */
export function plainToAss(text: string): string {
  return text
    .replace(/\r?\n/g, "\\N")
    .replace(/<(\/?)([ibu])>/gi, (_, close: string, tag: string) => `{\\${tag.toLowerCase()}${close ? 0 : 1}}`)
    .replace(/<[^>]+>/g, "");
}

/** Subtitles of one stream drawn on a canvas over the video, at the player's clock. */
export class SubtitleLayer {
  private readonly jassub: JASSUB;
  private readonly styled: boolean;
  /** Events already given to libass, by start and text, so a seek back doesn't add them twice. */
  private readonly seen = new Set<string>();
  private order = 0;

  constructor(
    canvas: HTMLCanvasElement,
    readonly stream: StreamInfo,
    header: Uint8Array | null,
    fonts: Attachment[],
  ) {
    this.styled = stream.codec === "ass" || stream.codec === "ssa";
    const script = this.styled && header ? utf8.decode(header) : PLAIN_HEADER;
    const fontFiles = fonts.filter((f) => /\.(ttf|otf|ttc|woff2?)$/i.test(f.name) || /font/i.test(f.mimeType));
    this.jassub = new JASSUB({
      canvas,
      subContent: script,
      fonts: fontFiles.map((f) => f.data),
      queryFonts: "local",
    });
  }

  ready(): Promise<void> {
    return this.jassub.ready;
  }

  /** One subtitle packet from the demuxer. */
  add(packet: TimedPacket) {
    if (packet.time === null) return;
    const text = utf8.decode(packet.packet.data);
    const startMs = Math.round(packet.time * 1000);
    const durationMs = Math.round((packet.duration ?? 5) * 1000);
    const key = `${startMs}|${text}`;
    if (this.seen.has(key)) return;
    this.seen.add(key);
    // Matroska ASS packets are "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text"; others are text.
    const chunk = this.styled ? text : `${this.order++},0,Default,,0,0,0,,${plainToAss(text)}`;
    this.jassub.renderer.processChunk(chunk, startMs, durationMs);
  }

  /** Draws the subtitles for file time [time] over a video of [width]×[height]. */
  render(time: number, width: number, height: number) {
    void this.jassub.manualRender({ mediaTime: time, expectedDisplayTime: performance.now(), width, height });
  }

  resize() {
    void this.jassub.resize(true);
  }

  async destroy() {
    await this.jassub.destroy().catch(() => {});
  }
}
