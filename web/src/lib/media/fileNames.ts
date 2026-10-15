import { normalize } from "../local/library";
import { baseName } from "../syncplay/filenames";

/**
 * Filename helpers shared by media sources (FileNames.kt; server paths may use either separator): Syncplay's
 * normalisation for the looser filename match, and a best-effort parser of release names for the title search that
 * comes after it.
 */

export { baseName, normalize };

export const AUDIO_EXTENSIONS: ReadonlySet<string> = new Set(["mp3", "flac", "aac", "m4a", "ogg", "opus", "wav", "wma", "alac", "aiff"]);

export function isAudio(name: string): boolean {
  const ext = name.split(".").pop()?.toLowerCase();
  return ext !== undefined && AUDIO_EXTENSIONS.has(ext);
}

const episodeRegex = /(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,4})(?!\d)/i;
const altEpisodeRegex = /(?<![a-z0-9])(\d{1,2})x(\d{2,4})(?!\d)/i;
const yearRegex = /(?<![0-9])(19\d{2}|20\d{2})(?![0-9])/g;
const bracketGroups = /\[[^\]]*\]|\{[^}]*\}/g;
/** Anime release style: "Show - 05", "Show - 05v2 (1080p)", "Show - E05". */
const dashEpisode = /\s-\s*(?:e|ep|episode\s*)?(\d{1,4})(?:v\d)?(?=\s|$|[(\[._])/i;
const wordEpisode = /(?<![a-z0-9])(?:e|ep|episode\s*)(\d{1,4})(?:v\d)?(?!\d)/i;
const seasonWords = /(?<![a-z0-9])(?:s(\d{1,2})|season\s*(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)\s+season)(?![a-z0-9])/gi;
const bareEpisode = /^(\d{1,4})(?:v\d)?$/i;
const seasonFolder = /^(?:season|series|s)\s*(\d{1,2})$/i;
const qualityTokens =
  /\b(2160p|1080p|720p|480p|4k|uhd|hdr10?|dv|x26[45]|h\.?26[45]|hevc|avc|10bit|8bit|bluray|bdrip|brrip|web[- .]?dl|webrip|web|hdtv|remux|dual[ .-]audio|multi|aac\d?(\.\d)?|ac3|eac3|dts(-hd)?|truehd|atmos|flac|opus)\b/gi;

/** The name without a short extension ("a.mkv" → "a"; a dot further back is part of the name). */
export function withoutExtension(name: string): string {
  const dot = name.lastIndexOf(".");
  return dot > 0 && name.length - dot <= 5 ? name.slice(0, dot) : name;
}

export interface Parsed {
  title: string;
  season: number | null;
  episode: number | null;
  year: number | null;
}

const lastMatch = (re: RegExp, text: string): RegExpMatchArray | null => {
  let last: RegExpMatchArray | null = null;
  for (const m of text.matchAll(re)) last = m;
  return last;
};

const removeRange = (text: string, m: RegExpMatchArray) => text.slice(0, m.index) + text.slice((m.index ?? 0) + m[0].length);

/** Kotlin's trim(' ', '-'): spaces and dashes off both ends. */
const trimSpacesAndDashes = (s: string) => s.replace(/^[ -]+|[ -]+$/g, "");

function cleanTitle(part: string): string {
  return trimSpacesAndDashes(
    part
      .replace(qualityTokens, " ")
      .replace(/[._]/g, " ")
      .replace(/[()[\]]/g, " ")
      .replace(/\s+-\s*$|^\s*-\s+/g, " ")
      .replace(/\s+/g, " "),
  );
}

/** Best-effort "Show - S01E02 - Name [group].mkv" / "Movie (2020) 1080p.mkv" parser. */
export function parse(fileName: string): Parsed {
  const stem = withoutExtension(baseName(fileName)).replace(bracketGroups, " ");
  const ep = episodeRegex.exec(stem) ?? altEpisodeRegex.exec(stem);
  let season: number | null = null;
  let episode: number | null = null;
  let titlePart = stem;
  if (ep) {
    season = Number(ep[1]);
    episode = Number(ep[2]);
    titlePart = stem.slice(0, ep.index);
  }
  const yearMatch = lastMatch(yearRegex, titlePart);
  const year = yearMatch && (yearMatch.index ?? 0) > 0 ? yearMatch : null;
  if (year && !ep) titlePart = titlePart.slice(0, year.index);
  return { title: cleanTitle(titlePart), season, episode, year: year ? Number(year[0]) : null };
}

/**
 * Which episode of which show a video file is, for list tracking: [parse]'s `S01E02`/`1x02`, plus anime release
 * names (`[Group] Show - 05 (1080p)`, `Show S2 - 05`, `Show 2nd Season - 05`, `Show E05`, `- 05v2`). [directory]
 * (e.g. `Show/Season 2`) gives the season, and the title when the file name has none. Null for files that don't look
 * like an episode (movies).
 */
export function parseEpisode(fileName: string, directory: string | null = null): Parsed | null {
  const base = parse(fileName);
  let { title, season, episode } = base;
  let year: number | null = null;
  if (episode === null) {
    let stem = withoutExtension(baseName(fileName)).replace(bracketGroups, " ").replace(/_/g, " ");
    if (!stem.trim().includes(" ")) stem = stem.replace(/\./g, " ");
    // "05.mkv" in a show's folder is just the episode number.
    const marker = dashEpisode.exec(stem) ?? wordEpisode.exec(stem) ?? bareEpisode.exec(stem.trim());
    if (!marker) return null;
    episode = Number(marker[1]);
    let head = stem.slice(0, marker.index);
    const s = lastMatch(seasonWords, head);
    if (s) {
      season = Number(s.slice(1).find((g) => g !== undefined && g !== ""));
      head = removeRange(head, s);
    }
    const y = lastMatch(yearRegex, head);
    if (y && (y.index ?? 0) > 0) {
      year = Number(y[0]);
      head = removeRange(head, y);
    }
    title = cleanTitle(head);
  }
  const folders = (directory ?? "")
    .split(/[/\\]/)
    .map((f) => f.trim())
    .filter((f) => f.length > 0);
  const lastFolder = folders.at(-1);
  if (season === null && lastFolder !== undefined) {
    const m = seasonFolder.exec(lastFolder);
    if (m) season = Number(m[1]);
  }
  if (title.trim() === "") {
    const showFolder = [...folders].reverse().find((f) => !seasonFolder.test(f));
    title = showFolder !== undefined ? cleanTitle(showFolder) : "";
  }
  if (title.trim() === "") return null;
  return { title, season, episode, year };
}
