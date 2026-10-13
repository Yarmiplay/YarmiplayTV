/**
 * MyAnimeList's data as the app uses it (MalModels.kt). MyAnimeList's answers are read leniently, as the apps do:
 * unknown fields are ignored, and missing or null ones take their defaults.
 */

/** A signed-in MyAnimeList account's OAuth tokens. [expiresAtMillis] is when [accessToken] stops working. */
export interface MalTokens {
  accessToken: string;
  refreshToken: string;
  expiresAtMillis: number;
}

export interface MalUser {
  id: number;
  name: string;
  picture: string | null;
}

export interface MalPicture {
  medium: string | null;
  large: string | null;
}

export interface MalAlternativeTitles {
  synonyms: string[];
  en: string | null;
  ja: string | null;
}

export type MalStatus = "watching" | "completed" | "on_hold" | "dropped" | "plan_to_watch";

/** The list statuses in MyAnimeList's order, with their labels. */
export const MAL_STATUSES: readonly MalStatus[] = ["watching", "completed", "on_hold", "dropped", "plan_to_watch"];

export const malStatusLabel: Record<MalStatus, string> = {
  watching: "Watching",
  completed: "Completed",
  on_hold: "On hold",
  dropped: "Dropped",
  plan_to_watch: "Plan to watch",
};

/** [value] as a list status, also from the apps' enum names ("PLAN_TO_WATCH"); null for anything else. */
export function malStatusOf(value: string | null | undefined): MalStatus | null {
  const v = value?.toLowerCase();
  return (MAL_STATUSES as readonly string[]).includes(v ?? "") ? (v as MalStatus) : null;
}

/** Your list entry for an anime, as MyAnimeList returns it. */
export interface MalListStatus {
  status: string | null;
  score: number;
  watchedEpisodes: number;
  isRewatching: boolean;
  timesRewatched: number;
  startDate: string | null;
  finishDate: string | null;
}

export const emptyListStatus = (): MalListStatus => ({
  status: null,
  score: 0,
  watchedEpisodes: 0,
  isRewatching: false,
  timesRewatched: 0,
  startDate: null,
  finishDate: null,
});

/** The entry's status as one of [MalStatus], or null when unknown. */
export const statusValue = (s: MalListStatus | null | undefined): MalStatus | null => malStatusOf(s?.status);

export interface MalRelated {
  node: MalAnime;
  relationType: string;
}

/** An anime entry. MyAnimeList content: shown as it comes, never edited. */
export interface MalAnime {
  id: number;
  title: string;
  mainPicture: MalPicture | null;
  alternativeTitles: MalAlternativeTitles | null;
  startDate: string | null;
  mediaType: string | null;
  /** 0 while unknown (often while airing). */
  numEpisodes: number;
  status: string | null;
  mean: number | null;
  relatedAnime: MalRelated[];
  myListStatus: MalListStatus | null;
}

/** An entry with only what's given; the rest as MyAnimeList leaves it when unknown. */
export const malAnime = (fields: Partial<MalAnime> & { id: number; title: string }): MalAnime => ({
  mainPicture: null,
  alternativeTitles: null,
  startDate: null,
  mediaType: null,
  numEpisodes: 0,
  status: null,
  mean: null,
  relatedAnime: [],
  myListStatus: null,
  ...fields,
});

export function animeYear(a: MalAnime): number | null {
  const head = a.startDate?.slice(0, 4) ?? "";
  return /^[+-]?\d+$/.test(head) ? Number(head) : null;
}

export const animeUrl = (a: MalAnime): string => `https://myanimelist.net/anime/${a.id}`;

/** A series entry (TV or web), as opposed to a movie, OVA, special or music video. */
export const isSeries = (a: MalAnime): boolean => a.mediaType === "tv" || a.mediaType === "ona";

/** A season of a series (TV or web), not a movie, OVA, special or a one- or two-episode web recap. */
export const isMainSeries = (a: MalAnime): boolean => isSeries(a) && !(a.mediaType === "ona" && a.numEpisodes >= 1 && a.numEpisodes <= 2);

export function animeTitles(a: MalAnime): string[] {
  const alt = a.alternativeTitles;
  return [a.title, alt?.en, alt?.ja].filter((t): t is string => t != null).concat(alt?.synonyms ?? []);
}

/** A change to your list entry; unset fields stay as they are. Dates are `YYYY-MM-DD`. */
export interface MalListUpdate {
  status?: MalStatus | null;
  score?: number | null;
  watchedEpisodes?: number | null;
  isRewatching?: boolean | null;
  timesRewatched?: number | null;
  startDate?: string | null;
  finishDate?: string | null;
}

const clamp = (n: number, min: number, max: number) => Math.min(Math.max(n, min), max);

/** The form fields of `PATCH /anime/{id}/my_list_status`, in MyAnimeList's order. */
export function formFields(u: MalListUpdate): [string, string][] {
  const out: [string, string][] = [];
  if (u.status != null) out.push(["status", u.status]);
  if (u.score != null) out.push(["score", String(clamp(u.score, 0, 10))]);
  if (u.watchedEpisodes != null) out.push(["num_watched_episodes", String(Math.max(u.watchedEpisodes, 0))]);
  if (u.isRewatching != null) out.push(["is_rewatching", String(u.isRewatching)]);
  if (u.timesRewatched != null) out.push(["num_times_rewatched", String(Math.max(u.timesRewatched, 0))]);
  if (u.startDate != null) out.push(["start_date", u.startDate]);
  if (u.finishDate != null) out.push(["finish_date", u.finishDate]);
  return out;
}

/** The update that puts [status] back (for Undo). */
export const restoring = (status: MalListStatus): MalListUpdate => ({
  status: statusValue(status),
  score: status.score,
  watchedEpisodes: status.watchedEpisodes,
  isRewatching: status.isRewatching,
  timesRewatched: status.timesRewatched,
  startDate: status.startDate,
  finishDate: status.finishDate,
});

/** A MyAnimeList sign-in, kept on this device only: no password, just the OAuth tokens. */
export interface MalAccount {
  tokens: MalTokens;
  userName: string;
  picture: string | null;
}

/** A list update for anime [animeId] waiting to be sent. */
export interface MalQueued {
  animeId: number;
  update: MalListUpdate;
}

// --- Reading MyAnimeList's JSON ----------------------------------------------------------------------------------

type Json = Record<string, unknown>;

const isObject = (v: unknown): v is Json => typeof v === "object" && v !== null && !Array.isArray(v);
const str = (o: Json, key: string): string | null => (typeof o[key] === "string" ? (o[key] as string) : null);
const num = (o: Json, key: string, fallback = 0): number => (typeof o[key] === "number" && Number.isFinite(o[key]) ? (o[key] as number) : fallback);
const int = (o: Json, key: string): number => Math.trunc(num(o, key));
const bool = (o: Json, key: string): boolean => o[key] === true;

/** Thrown for an answer that isn't what MyAnimeList sends. */
export class MalFormatError extends Error {}

export function parseListStatus(v: unknown): MalListStatus | null {
  if (!isObject(v)) return null;
  return {
    status: str(v, "status"),
    score: int(v, "score"),
    watchedEpisodes: int(v, "num_episodes_watched"),
    isRewatching: bool(v, "is_rewatching"),
    timesRewatched: int(v, "num_times_rewatched"),
    startDate: str(v, "start_date"),
    finishDate: str(v, "finish_date"),
  };
}

export function parseAnime(v: unknown): MalAnime {
  if (!isObject(v) || typeof v.id !== "number" || typeof v.title !== "string") throw new MalFormatError("MyAnimeList sent something unreadable");
  const picture = isObject(v.main_picture) ? { medium: str(v.main_picture, "medium"), large: str(v.main_picture, "large") } : null;
  const alt = isObject(v.alternative_titles)
    ? {
        synonyms: Array.isArray(v.alternative_titles.synonyms) ? v.alternative_titles.synonyms.filter((s): s is string => typeof s === "string") : [],
        en: str(v.alternative_titles, "en"),
        ja: str(v.alternative_titles, "ja"),
      }
    : null;
  const related: MalRelated[] = [];
  if (Array.isArray(v.related_anime)) {
    for (const r of v.related_anime) {
      if (!isObject(r)) continue;
      try {
        related.push({ node: parseAnime(r.node), relationType: str(r, "relation_type") ?? "" });
      } catch {
        // A related entry MyAnimeList sent without an id or title can't be followed.
      }
    }
  }
  return {
    id: v.id,
    title: v.title,
    mainPicture: picture,
    alternativeTitles: alt,
    startDate: str(v, "start_date"),
    mediaType: str(v, "media_type"),
    numEpisodes: int(v, "num_episodes"),
    status: str(v, "status"),
    mean: typeof v.mean === "number" ? v.mean : null,
    relatedAnime: related,
    myListStatus: parseListStatus(v.my_list_status),
  };
}

/** A page of `{data: [{node, list_status}]}`. */
export function parsePage(v: unknown): { node: MalAnime; listStatus: MalListStatus | null }[] {
  if (!isObject(v)) throw new MalFormatError("MyAnimeList sent something unreadable");
  const data = Array.isArray(v.data) ? v.data : [];
  return data.filter(isObject).map((d) => ({ node: parseAnime(d.node), listStatus: parseListStatus(d.list_status) }));
}

export function parseUser(v: unknown): MalUser {
  if (!isObject(v) || typeof v.name !== "string") throw new MalFormatError("MyAnimeList sent something unreadable");
  return { id: int(v, "id"), name: v.name, picture: str(v, "picture") };
}

export function parseTokenResponse(v: unknown): { accessToken: string; refreshToken: string; expiresIn: number } {
  if (!isObject(v) || typeof v.access_token !== "string" || typeof v.refresh_token !== "string" || typeof v.expires_in !== "number") {
    throw new MalFormatError("MyAnimeList sent something unreadable");
  }
  return { accessToken: v.access_token, refreshToken: v.refresh_token, expiresIn: v.expires_in };
}

// --- Shown on the MyAnimeList screens ---------------------------------------------------------------------------

/** "Watching · 3 of 12 watched · 8/10", or that it isn't on the list. */
export function listSummary(a: MalAnime): string {
  const list = a.myListStatus;
  if (!list) return "Not on your list";
  const status = statusValue(list);
  const of = a.numEpisodes > 0 ? ` of ${a.numEpisodes}` : "";
  return [status ? malStatusLabel[status] : null, `${list.watchedEpisodes}${of} watched`, list.score > 0 ? `${list.score}/10` : null]
    .filter((s): s is string => s != null)
    .join(" · ");
}

/** "TV · 2023 · 28 episodes". */
export function animeDetails(a: MalAnime): string {
  const year = animeYear(a);
  return [a.mediaType?.toUpperCase() ?? null, year != null ? String(year) : null, a.numEpisodes > 0 ? `${a.numEpisodes} episodes` : null]
    .filter((s): s is string => s != null)
    .join(" · ");
}

// --- Kept on this device ----------------------------------------------------------------------------------------

/** A saved account, or null for anything that isn't one. */
export function readAccount(v: unknown): MalAccount | null {
  if (!isObject(v) || !isObject(v.tokens) || typeof v.userName !== "string") return null;
  const t = v.tokens;
  if (typeof t.accessToken !== "string" || typeof t.refreshToken !== "string" || typeof t.expiresAtMillis !== "number") return null;
  return {
    tokens: { accessToken: t.accessToken, refreshToken: t.refreshToken, expiresAtMillis: t.expiresAtMillis },
    userName: v.userName,
    picture: str(v, "picture"),
  };
}

/** A saved list update; fields that aren't what they should be are left out. */
export function readUpdate(v: unknown): MalListUpdate {
  if (!isObject(v)) return {};
  const u: MalListUpdate = {};
  const status = malStatusOf(str(v, "status"));
  if (status) u.status = status;
  for (const key of ["score", "watchedEpisodes", "timesRewatched"] as const) if (typeof v[key] === "number") u[key] = v[key] as number;
  if (typeof v.isRewatching === "boolean") u.isRewatching = v.isRewatching;
  for (const key of ["startDate", "finishDate"] as const) if (typeof v[key] === "string") u[key] = v[key] as string;
  return u;
}

export function readQueue(v: unknown): MalQueued[] {
  if (!Array.isArray(v)) return [];
  return v.filter(isObject).filter((q) => typeof q.animeId === "number").map((q) => ({ animeId: q.animeId as number, update: readUpdate(q.update) }));
}

/** [u] without its unset fields, as it is kept. */
export function compactUpdate(u: MalListUpdate): MalListUpdate {
  return Object.fromEntries(Object.entries(u).filter(([, value]) => value != null)) as MalListUpdate;
}
