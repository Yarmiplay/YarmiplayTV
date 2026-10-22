import JASSUB from "jassub";
import { PgsRenderer } from "libpgs";
import type { Attachment, StreamInfo, TimedPacket } from "./demuxer";

/** Text subtitle codecs drawn with libass; PGS (Blu-ray pictures) go to PgsLayer. */
export const TEXT_SUBTITLES = new Set(["ass", "ssa", "subrip", "srt", "webvtt", "text", "mov_text"]);

/** A plain script for subtitles that bring no styles of their own (SRT, WebVTT): white text with a black outline. */
export const PLAIN_HEADER = `[Script Info]
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

/** `00:00:01,500` or `0:00:01.5` as ASS centiseconds (`0:00:01.50`). */
function assTime(raw: string): string {
  const m = raw.trim().match(/(?:(\d+):)?(\d+):(\d+)[.,](\d+)/);
  if (!m) return "0:00:00.00";
  const hours = Number(m[1] ?? 0);
  const frac = `${m[4]}00`.slice(0, 2);
  return `${hours}:${m[2].padStart(2, "0")}:${m[3].padStart(2, "0")}.${frac}`;
}

/** An SRT or WebVTT file as one ASS script. An ASS or SSA file is returned as it is. */
export function externalScript(text: string): string {
  const body = text.replace(/^\uFEFF/, "").replace(/\r\n/g, "\n");
  if (/^\s*\[Script Info\]/m.test(body)) return body;
  const cues = body.replace(/^WEBVTT[^\n]*\n/, "").split(/\n\s*\n/);
  const lines: string[] = [];
  for (const cue of cues) {
    const rows = cue.split("\n").filter((row) => row.trim() !== "" && !row.startsWith("NOTE") && !row.startsWith("STYLE") && !row.startsWith("REGION"));
    const timeRow = rows.findIndex((row) => row.includes("-->"));
    if (timeRow < 0) continue;
    const [startRaw, endRaw] = rows[timeRow].split("-->");
    const textLines = rows.slice(timeRow + 1).join("\n");
    const end = endRaw?.trim().split(/\s+/)[0];
    if (!startRaw?.trim() || !end || textLines.trim() === "") continue;
    lines.push(`Dialogue: 0,${assTime(startRaw)},${assTime(end)},Default,,0,0,0,,${plainToAss(textLines)}`);
  }
  return `${PLAIN_HEADER}${lines.join("\n")}\n`;
}

/** A whole subtitle file drawn with libass, with no demuxer stream of its own. */
export class ScriptSubtitles {
  private readonly jassub: JASSUB;

  constructor(canvas: HTMLCanvasElement, script: string) {
    this.jassub = new JASSUB({ canvas, subContent: script, queryFonts: "local" });
  }

  ready(): Promise<void> {
    return this.jassub.ready;
  }

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

/** PGS (Blu-ray picture) subtitles, drawn with libpgs. */
export const PGS = "hdmv_pgs_subtitle";

/**
 * PGS subtitles of one stream. Matroska stores each display set without the `.sup` file's per-segment headers, so the
 * packets read so far are put back into a `.sup` (a "PG" marker and the time before every segment) and handed to
 * libpgs again, at most twice a second, as more arrive.
 */
export class PgsLayer {
  private readonly renderer: PgsRenderer;
  private readonly parts: Uint8Array[] = [];
  private readonly seen = new Set<number>();
  private timer: ReturnType<typeof setTimeout> | undefined;
  private size = 0;

  constructor(
    canvas: HTMLCanvasElement,
    readonly stream: StreamInfo,
  ) {
    // On the main thread: the package's worker needs its own URL, and PGS is light to draw.
    type Options = ConstructorParameters<typeof PgsRenderer>[0];
    this.renderer = new PgsRenderer({ canvas, mode: "mainThread" as Options["mode"], aspectRatio: "contain" });
  }

  add(packet: TimedPacket) {
    if (packet.time === null || this.seen.has(packet.time)) return;
    this.seen.add(packet.time);
    const pts = Math.round(packet.time * 90_000) >>> 0;
    const data = packet.packet.data;
    for (let at = 0; at + 3 <= data.length; ) {
      const length = (data[at + 1] << 8) | data[at + 2];
      const segment = new Uint8Array(10 + 3 + length);
      const view = new DataView(segment.buffer);
      segment[0] = 0x50; // P
      segment[1] = 0x47; // G
      view.setUint32(2, pts);
      view.setUint32(6, 0);
      segment.set(data.subarray(at, at + 3 + length), 10);
      this.parts.push(segment);
      this.size += segment.length;
      at += 3 + length;
    }
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.reload(), 500);
  }

  private reload() {
    // Display sets in time order, as a .sup file has them.
    const all = new Uint8Array(this.size);
    const sorted = [...this.parts].sort((a, b) => new DataView(a.buffer).getUint32(2) - new DataView(b.buffer).getUint32(2));
    let at = 0;
    for (const p of sorted) {
      all.set(p, at);
      at += p.length;
    }
    void this.renderer.loadFromBuffer(all.buffer);
  }

  render(time: number) {
    this.renderer.renderAtTimestamp(time);
  }

  destroy() {
    clearTimeout(this.timer);
    this.renderer.dispose();
  }
}
