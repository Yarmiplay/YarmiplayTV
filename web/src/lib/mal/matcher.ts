import { MalClient } from "./client";
import type { EpisodeInfo } from "./episode";
import { animeTitles, animeYear, isMainSeries, isSeries, type MalAnime, type MalListStatus } from "./models";

/** What [MalMatcher] reads from MyAnimeList; [MalClient] behind a cache in the app, a fake in tests. */
export interface MalCatalog {
  search(query: string): Promise<MalAnime[]>;
  anime(id: number): Promise<MalAnime>;
  /** What the user is watching now, to tell which of two readings fits. */
  watching(): Promise<[MalAnime, MalListStatus][]>;
  /** What the user has completed: a franchise they know makes a weak title match trustworthy. */
  completed?(): Promise<[MalAnime, MalListStatus][]>;
}

/**
 * A show matched by hand (or earlier): episode `E` of the show's season is episode `E - offset` of anime [malId].
 * It's a place in the franchise, so later episodes continue into its sequels and earlier ones go back to its prequels.
 */
export interface MalMapping {
  malId: number;
  offset: number;
}

/** Which anime entry and which of its episodes something is. [sure] matches are applied without asking. */
export interface MalMatch {
  anime: MalAnime;
  episode: number;
  sure: boolean;
  /** Other entries it might be, for the picker: the franchise's seasons first, then other search results. */
  candidates: MalAnime[];
}

interface Reading {
  anime: MalAnime;
  episode: number;
  known: boolean;
}

/** How far the franchise walk goes, so a long franchise can't cost many requests. */
export const MAX_WALK = 15;
/** A title match good enough on its own. */
export const STRONG_TITLE = 0.8;
/** A title match good enough when the episode counting leaves exactly one answer. */
export const FAIR_TITLE = 0.6;

const seasonSuffix =
  /[\s:,-]*(?:the\s+)?(?:season\s*\d+|\d+(?:st|nd|rd|th)\s+season|s\d+|part\s*(?:\d+|[ivx]+)|\d+(?:st|nd|rd|th)\s+cour|\(second half\))\s*$/iu;
const continuation = /\bpart\s*(?:[2-9]|ii|iii|iv|v)\b|\b(?:2nd|second|3rd|third)\s+cour\b|\(second half\)|kanketsu[\s-]?hen|\bthe final chapters?\b/i;

/**
 * Finds the MyAnimeList entry and episode for a library episode. MyAnimeList has an entry per season, and often one
 * per part of a season ("Season 3 Part 2"), while libraries number episodes per season or from the start of the show
 * ("absolute"). The franchise's series entries are found in airing order by following prequels back to the first and
 * sequels forward (movies, OVAs, specials and short recaps are skipped). Parts are grouped with the season they
 * continue, so season `S` episode `E` is counted through the `S`th group's parts, and an absolute episode through
 * every entry. When the readings disagree, or a length is unknown, the user's list decides; only then is the match
 * unsure and the user picks.
 */
export class MalMatcher {
  constructor(private readonly catalog: MalCatalog) {}

  async match(info: EpisodeInfo, saved: MalMapping | null = null): Promise<MalMatch | null> {
    const e = info.episode;
    if (e == null || e <= 0) return null;
    if (saved) {
      const m = await this.fromMapping(saved, e);
      if (m) return m;
    }
    if (info.malId != null) {
      const anime = await this.catalog.anime(info.malId);
      if (anime.numEpisodes === 0 || e <= anime.numEpisodes) return { anime, episode: e, sure: true, candidates: [anime] };
      const chain = [anime, ...(await this.sequels(anime))];
      const r = countThrough(chain, e, chain[chain.length - 1].id);
      if (r) return { anime: r.anime, episode: r.episode, sure: r.known, candidates: chain };
    }

    const { results, scored } = await this.search(info);
    const top = scored[0];
    if (!top) return null;
    const [best, score] = top;
    const chain = await this.franchise(best);
    const others = results.filter((r) => !chain.some((c) => c.id === r.id));
    const candidates = [...chain, ...others];

    const readings = this.readings(chain, info.season, e);
    if (readings.length === 0) return { anime: best, episode: e, sure: false, candidates };
    let pick: Reading;
    if (readings.length === 1 && readings[0].known) pick = readings[0];
    else {
      const fromList = await this.fromList(readings);
      if (!fromList) return { anime: readings[0].anime, episode: readings[0].episode, sure: false, candidates };
      pick = fromList;
    }
    // One answer from the counting (or the list's) and a fair title, or a franchise the user's list already has.
    const sure = score >= FAIR_TITLE || (await this.onList(chain));
    return { anime: pick.anime, episode: pick.episode, sure, candidates };
  }

  /**
   * Season `S` episode `E`: counted through the `S`th group's parts, or, for libraries that number a later season from
   * the start of the show, counted through everything when that lands in the same group. Season 1 (or none):
   * counted through everything.
   */
  private readings(chain: MalAnime[], season: number | null, e: number): Reading[] {
    const last = chain[chain.length - 1].id;
    const absolute = countThrough(chain, e, last);
    if (season == null || season <= 1) return absolute ? [absolute] : [];
    const group = MalMatcher.groups(chain)[season - 1];
    if (!group) return [];
    const bySeason = countThrough(group, e, last);
    const absoluteInSeason = absolute && group.some((a) => a.id === absolute.anime.id) ? absolute : null;
    const out: Reading[] = [];
    for (const r of [bySeason, absoluteInSeason]) {
      if (r && !out.some((o) => o.anime.id === r.anime.id && o.episode === r.episode)) out.push(r);
    }
    return out;
  }

  /** A saved match: its entry's place in the franchise, so earlier and later episodes map too. */
  private async fromMapping(saved: MalMapping, e: number): Promise<MalMatch | null> {
    const start = await this.catalog.anime(saved.malId);
    const local = e - saved.offset;
    if (local >= 1) {
      const forward = [start, ...(await this.sequels(start))];
      const r = countThrough(forward, local, forward[forward.length - 1].id);
      return r ? { anime: r.anime, episode: r.episode, sure: r.known, candidates: forward } : null;
    }
    // Before the matched entry: count from the first entry, which needs every length before it.
    const chain = await this.franchise(start);
    const index = chain.findIndex((a) => a.id === start.id);
    if (index < 0 || chain.slice(0, index).some((a) => a.numEpisodes === 0)) return null;
    const absolute = chain.slice(0, index).reduce((sum, a) => sum + a.numEpisodes, 0) + local;
    const r = countThrough(chain, absolute, chain[chain.length - 1].id);
    return r ? { anime: r.anime, episode: r.episode, sure: r.known, candidates: chain } : null;
  }

  /** The reading the user's list already points at: the entry they're watching, at the episode before this one. */
  private async fromList(readings: Reading[]): Promise<Reading | null> {
    const watching = await this.catalog.watching().catch(() => [] as [MalAnime, MalListStatus][]);
    return readings.find((r) => watching.some(([a, s]) => a.id === r.anime.id && s.watchedEpisodes === r.episode - 1)) ?? null;
  }

  /** Whether the user's list has any entry of the franchise [chain]. */
  private async onList(chain: MalAnime[]): Promise<boolean> {
    const ids = new Set(chain.map((a) => a.id));
    let listed: [MalAnime, MalListStatus][];
    try {
      listed = [...(await this.catalog.watching()), ...((await this.catalog.completed?.()) ?? [])];
    } catch {
      listed = [];
    }
    return listed.some(([a]) => ids.has(a.id));
  }

  /**
   * The search results for the show and how well each matches its name, best first. One search when the name
   * matches well; otherwise also its part before a comma or colon and its first words, pooled.
   */
  private async search(info: EpisodeInfo): Promise<{ results: MalAnime[]; scored: [MalAnime, number][] }> {
    const pooled = new Map<number, MalAnime>();
    const scored = (): [MalAnime, number][] =>
      [...pooled.values()].map((a): [MalAnime, number] => [a, MalMatcher.titleScore(info.title, a, info.year ?? null)]).sort((x, y) => y[1] - x[1]);
    const queries = MalMatcher.searchQueries(info.title);
    for (let i = 0; i < queries.length; i++) {
      if (i > 0 && (scored()[0]?.[1] ?? 0) >= STRONG_TITLE) break;
      const found = await this.catalog.search(queries[i]).catch(() => [] as MalAnime[]);
      for (const a of found) if (!pooled.has(a.id)) pooled.set(a.id, a);
    }
    return { results: [...pooled.values()], scored: scored() };
  }

  /** The franchise's series entries in airing order: prequels back to the first, then sequels forward. */
  private async franchise(anime: MalAnime): Promise<MalAnime[]> {
    let first = await this.catalog.anime(anime.id);
    const seen = new Set([first.id]);
    while (seen.size <= MAX_WALK) {
      const prequel = await this.related(first, "prequel", seen);
      if (!prequel) break;
      first = prequel;
      seen.add(first.id);
    }
    const chain = [first, ...(await this.sequels(first))].filter(isMainSeries);
    return chain.length > 0 ? chain : [first];
  }

  /** The sequels after [anime], in order (through movies, specials and recaps, which aren't included). */
  private async sequels(anime: MalAnime): Promise<MalAnime[]> {
    const out: MalAnime[] = [];
    const seen = new Set([anime.id]);
    let current = await this.catalog.anime(anime.id);
    while (out.length < MAX_WALK && seen.size <= MAX_WALK * 2) {
      const next = await this.related(current, "sequel", seen);
      if (!next) break;
      current = next;
      seen.add(current.id);
      if (isMainSeries(current)) out.push(current);
    }
    return out;
  }

  /** The [relation] of [anime] not seen yet; with several, the series entry (a season rather than a movie). */
  private async related(anime: MalAnime, relation: string, seen: Set<number>): Promise<MalAnime | null> {
    const nodes = anime.relatedAnime.filter((r) => r.relationType === relation && !seen.has(r.node.id));
    if (nodes.length === 0) return null;
    if (nodes.length === 1) return this.catalog.anime(nodes[0].node.id);
    const entries: MalAnime[] = [];
    for (const n of nodes) entries.push(await this.catalog.anime(n.node.id));
    return entries.find(isMainSeries) ?? entries[0];
  }

  /** The show's name without a trailing "Season 2", "2nd Season" or "Part 2": the search finds the franchise. */
  static searchTitle(name: string): string {
    let s = name.trim();
    for (;;) {
      const next = s.replace(seasonSuffix, "").trim();
      if (next === s || next === "") return s;
      s = next;
    }
  }

  /**
   * What to search for: the name (cut at a word boundary to MyAnimeList's 64 characters), its part before a
   * comma or colon, and its first four and three words, without repeats.
   */
  static searchQueries(name: string): string[] {
    const title = MalMatcher.searchTitle(name);
    const words = title.split(/\s+/).filter((w) => w !== "");
    const beforeComma = title.split(/[,:]/)[0].trim();
    const queries = [
      MalClient.searchQuery(title),
      beforeComma.length >= 3 ? beforeComma : null,
      words.length > 4 ? words.slice(0, 4).join(" ") : null,
      words.length > 3 ? words.slice(0, 3).join(" ") : null,
    ]
      .filter((q): q is string => q != null)
      .map((q) => MalClient.searchQuery(q))
      .filter((q) => q.length >= 3);
    return [...new Set(queries)];
  }

  /**
   * 0..1: how well [name] matches one of [anime]'s titles, season and part markers left out on both sides:
   * the same title, the name being the title's start (or its part before a comma or colon), or how many words
   * they share. Words a letter apart ("Tensai" and "Tensei") count as the same. Plus a little for the year.
   */
  static titleScore(name: string, anime: MalAnime, year: number | null = null): number {
    const want = words(MalMatcher.searchTitle(name));
    if (want.length === 0) return 0;
    let best = 0;
    for (const title of animeTitles(anime)) {
      const bare = MalMatcher.searchTitle(title);
      const have = words(bare);
      const head = words(bare.split(/[,:]/)[0]);
      let score: number;
      if (have.length === 0) score = 0;
      else if (same(want, have)) score = 1;
      else if (same(want, head) || startsWith(have, want)) score = 0.9;
      else score = (2 * want.filter((w) => have.some((h) => similar(w, h))).length) / (want.length + have.length);
      best = Math.max(best, score);
    }
    const animeYearValue = animeYear(anime);
    const yearBonus = year != null && animeYearValue != null && Math.abs(animeYearValue - year) <= 1 ? 0.05 : 0;
    const typeBonus = isSeries(anime) ? 0.02 : 0;
    return Math.min(best + yearBonus + typeBonus, 1);
  }

  /**
   * MyAnimeList's entries grouped into library seasons: an entry that continues the previous one ("Part 2",
   * "2nd Cour", "Kanketsu-hen", …) is part of its season.
   */
  static groups(chain: MalAnime[]): MalAnime[][] {
    const out: MalAnime[][] = [];
    for (const anime of chain) {
      if (out.length > 0 && animeTitles(anime).some((t) => continuation.test(t))) out[out.length - 1].push(anime);
      else out.push([anime]);
    }
    return out;
  }
}

/**
 * Episode [absolute] counted through [chain]: the entry, its episode, and whether every length on the way was
 * known. Nothing can be counted past an entry of unknown length (still airing); it counts as known only when it
 * is the franchise's last entry ([lastId]), so a new season's episodes land on it.
 */
function countThrough(chain: MalAnime[], absolute: number, lastId: number): Reading | null {
  if (absolute <= 0) return null;
  let left = absolute;
  for (const anime of chain) {
    if (anime.numEpisodes === 0) return { anime, episode: left, known: anime.id === lastId };
    if (left <= anime.numEpisodes) return { anime, episode: left, known: true };
    left -= anime.numEpisodes;
  }
  return null;
}

const same = (a: string[], b: string[]) => a.length === b.length && a.every((w, i) => similar(w, b[i]));
const startsWith = (have: string[], want: string[]) => have.length > want.length && same(have.slice(0, want.length), want);

/** The same word, or one letter apart in a word of 4 letters or more (two in 8 or more). */
function similar(a: string, b: string): boolean {
  if (a === b) return true;
  const len = Math.min(a.length, b.length);
  const allowed = len >= 8 ? 2 : len >= 4 ? 1 : 0;
  return allowed > 0 && Math.abs(a.length - b.length) <= allowed && distance(a, b) <= allowed;
}

function distance(a: string, b: string): number {
  let previous = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const row = new Array<number>(b.length + 1);
    row[0] = i;
    for (let j = 1; j <= b.length; j++) {
      row[j] = Math.min(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    previous = row;
  }
  return previous[b.length];
}

const normalize = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
const words = (s: string) => normalize(s).split(" ").filter((w) => w !== "");
