/** Text subtitle files the player can add beside a video. Mirrors SeekThumbs.kt. */
export const SUBTITLE_EXTENSIONS = new Set(["srt", "ass", "ssa", "vtt"]);

const TEXT_SUBTITLE_CODECS = new Set(["srt", "ass", "ssa", "vtt", "subrip", "webvtt"]);

export interface TrickplaySet {
  intervalMs: number;
  thumbWidth: number;
  thumbHeight: number;
  columns: number;
  rows: number;
  count: number;
}

export interface TrickplayTile {
  sheet: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

export function trickplayTile(set: TrickplaySet, seconds: number): TrickplayTile | null {
  if (set.intervalMs <= 0 || set.columns <= 0 || set.rows <= 0 || set.count <= 0) return null;
  if (set.thumbWidth <= 0 || set.thumbHeight <= 0) return null;
  const index = Math.min(set.count - 1, Math.max(0, Math.floor((Math.max(0, seconds) * 1000) / set.intervalMs)));
  const perSheet = set.columns * set.rows;
  const at = index % perSheet;
  return {
    sheet: Math.floor(index / perSheet),
    x: (at % set.columns) * set.thumbWidth,
    y: Math.floor(at / set.columns) * set.thumbHeight,
    width: set.thumbWidth,
    height: set.thumbHeight,
  };
}

export function trickplayWidth(widths: number[], wantedWidth = 320): number | null {
  const have = widths.filter((w) => w > 0).sort((a, b) => a - b);
  return have.find((w) => w >= wantedWidth) ?? have[have.length - 1] ?? null;
}

export function jellyfinSheetUrl(server: string, itemId: string, width: number, sheet: number, token: string): string {
  const root = server.replace(/\/+$/, "");
  return `${root}/Videos/${itemId}/Trickplay/${width}/${sheet}.jpg?api_key=${encodeURIComponent(token)}`;
}

export function plexPartId(partKey: string): string | null {
  return /\/library\/parts\/(\d+)/.exec(partKey)?.[1] ?? null;
}

export function plexPreviewUrl(server: string, partId: string, indexId: string, token: string, seconds: number): string {
  const root = server.replace(/\/+$/, "");
  const millis = Math.floor(Math.max(0, seconds) * 1000);
  return `${root}/library/parts/${partId}/indexes/${indexId}/${millis}?X-Plex-Token=${encodeURIComponent(token)}`;
}

export type StoredSeekFrames =
  | { kind: "jellyfin"; server: string; itemId: string; width: number; token: string; set: TrickplaySet }
  | { kind: "plex"; server: string; partId: string; indexId: string; token: string };

export function sheetUrl(frames: Extract<StoredSeekFrames, { kind: "jellyfin" }>, sheet: number): string {
  return jellyfinSheetUrl(frames.server, frames.itemId, frames.width, sheet, frames.token);
}

export function jpegUrl(frames: Extract<StoredSeekFrames, { kind: "plex" }>, seconds: number): string {
  return plexPreviewUrl(frames.server, frames.partId, frames.indexId, frames.token, seconds);
}

export interface ExternalSubtitle {
  url: string;
  title: string;
  language: string | null;
}

export function sidecarMatches(videoName: string, subtitleName: string): boolean {
  const ext = subtitleName.slice(subtitleName.lastIndexOf(".") + 1).toLowerCase();
  if (!SUBTITLE_EXTENSIONS.has(ext)) return false;
  const videoStem = videoName.slice(0, videoName.lastIndexOf(".")).toLowerCase();
  const stem = subtitleName.slice(0, subtitleName.lastIndexOf(".")).toLowerCase();
  if (stem === videoStem) return true;
  const dot = stem.lastIndexOf(".");
  if (dot < 0) return false;
  const lang = stem.slice(dot + 1);
  return stem.slice(0, dot) === videoStem && lang.length > 0 && lang.length <= 8 && /^[a-z]+$/.test(lang);
}

export function sidecarLanguage(fileName: string): string | null {
  const stem = fileName.slice(0, fileName.lastIndexOf("."));
  const dot = stem.lastIndexOf(".");
  if (dot < 0) return null;
  const lang = stem.slice(dot + 1);
  if (lang.length === 0 || lang.length > 8 || !/^[a-zA-Z]+$/.test(lang)) return null;
  return lang;
}

export function isTextSubtitleCodec(codec: string | null | undefined): boolean {
  return codec != null && TEXT_SUBTITLE_CODECS.has(codec.toLowerCase());
}

export function preferredSidecar(subs: ExternalSubtitle[], languages: string): ExternalSubtitle | null {
  if (subs.length === 0) return null;
  const wanted = languages.split(",").map((s) => s.trim().toLowerCase()).filter((s) => s !== "");
  return (
    subs.find((sub) => {
      const lang = sub.language?.toLowerCase();
      return lang != null && wanted.some((want) => lang === want || lang.startsWith(want));
    }) ?? subs[0]
  );
}

const offers = new Map<string, StoredSeekFrames>();

export function rememberSeek(url: string, frames: StoredSeekFrames) {
  offers.set(url, frames);
}

export function seekOffer(url: string): StoredSeekFrames | null {
  return offers.get(url) ?? null;
}

export function dropSeek(url: string) {
  offers.delete(url);
}
