import { describe, expect, it } from "vitest";
import type { EpisodeInfo } from "./episode";
import { MalMatcher, STRONG_TITLE, type MalCatalog, type MalMapping } from "./matcher";
import { isMainSeries, malAnime, type MalAnime, type MalListStatus } from "./models";

// Ported from MalMatcherTest.kt and MalMatcherSeasonsTest.kt.

const listStatus = (status: string, watchedEpisodes: number): MalListStatus => ({
  status,
  score: 0,
  watchedEpisodes,
  isRewatching: false,
  timesRewatched: 0,
  startDate: null,
  finishDate: null,
});

describe("MalMatcher", () => {
  /**
   * A franchise like many on MyAnimeList: four TV seasons (12, 12, 24, 16 episodes) with a movie and a special
   * among the sequels, and an unrelated show with a similar name.
   */
  const anime = (id: number, title: string, episodes: number, prequel: number | null, sequel: number | null, type = "tv", year: number | null = null) =>
    malAnime({
      id,
      title,
      mediaType: type,
      numEpisodes: episodes,
      startDate: year != null ? `${year}-04-01` : null,
      relatedAnime: [
        ...(prequel != null ? [{ node: malAnime({ id: prequel, title: "p" }), relationType: "prequel" }] : []),
        ...(sequel != null ? [{ node: malAnime({ id: sequel, title: "s" }), relationType: "sequel" }] : []),
      ],
    });
  const s1 = anime(100, "Hero Academy", 12, null, 101, "tv", 2016);
  const movie = anime(101, "Hero Academy: The Movie", 1, 100, 102, "movie");
  const s2 = anime(102, "Hero Academy 2nd Season", 12, 101, 103);
  const s3 = anime(103, "Hero Academy 3rd Season", 24, 102, 104);
  const special = anime(104, "Hero Academy: Special", 2, 103, 105, "special");
  const s4 = anime(105, "Hero Academy 4th Season", 16, 104, null);
  const other = anime(900, "Heroic Academy Tales", 10, null, null);
  const all = new Map([s1, movie, s2, s3, special, s4, other].map((a) => [a.id, a]));

  const catalog: MalCatalog = {
    search: async () => [s3, other, s1, s2],
    anime: async (id) => all.get(id)!,
    watching: async () => [],
  };
  const matcher = new MalMatcher(catalog);

  const ep = (season: number | null, episode: number | null, name = "Hero Academy", malId: number | null = null): EpisodeInfo => ({
    title: name,
    season,
    episode,
    seriesKey: name,
    malId,
  });
  const match = (info: EpisodeInfo, saved: MalMapping | null = null) => matcher.match(info, saved);
  const at = async (info: EpisodeInfo, saved: MalMapping | null = null) => {
    const m = (await match(info, saved))!;
    return [m.anime.id, m.episode];
  };

  it("season and episode pick that season's entry, skipping movies and specials", async () => {
    const m = (await match(ep(2, 5)))!;
    expect([m.anime.id, m.episode]).toEqual([102, 5]);
    expect(m.sure).toBe(true);
    expect(m.candidates.slice(0, 4).map((a) => a.id)).toEqual([100, 102, 103, 105]);
  });

  it("an absolute episode is counted through the seasons", async () => {
    // 12 + 12 + 24 = 48, so episode 50 is the 4th season's 2nd episode.
    const m = (await match(ep(1, 50)))!;
    expect([m.anime.id, m.episode]).toEqual([105, 2]);
    expect(m.sure).toBe(true);
    expect(await at(ep(null, 28))).toEqual([103, 4]);
  });

  it("a later season numbered from the start of the show lands in that season", async () => {
    // Season 4, numbered absolutely: episode 50 is still the 4th season's 2nd.
    const m = (await match(ep(4, 50)))!;
    expect([m.anime.id, m.episode]).toEqual([105, 2]);
    expect(m.sure).toBe(true);
  });

  it("an episode beyond the whole franchise is unsure", async () => {
    const m = (await match(ep(1, 200)))!;
    expect(m.sure).toBe(false);
    expect(m.candidates.some((a) => a.id === 900)).toBe(true);
  });

  it("a saved mapping is used first, and continues into the sequels", async () => {
    const m = (await match(ep(1, 15, "Totally different name"), { malId: 102, offset: 0 }))!;
    expect([m.anime.id, m.episode]).toEqual([103, 3]);
    expect(m.sure).toBe(true);
    expect(await at(ep(1, 6), { malId: 102, offset: 2 })).toEqual([102, 4]);
  });

  it("a MyAnimeList id from the media server is used directly", async () => {
    const m = (await match(ep(3, 7, "Hero Academy", 103)))!;
    expect([m.anime.id, m.episode]).toEqual([103, 7]);
    expect(m.sure).toBe(true);
  });

  it("a weak title match is never sure", async () => {
    expect((await match(ep(1, 3, "Something Else Entirely")))!.sure).toBe(false);
  });

  it("no episode number means no match", async () => {
    expect(await match({ title: "Hero Academy", season: 1, episode: null, seriesKey: "x" })).toBeNull();
  });

  it("titles are compared without the season suffix", () => {
    expect(MalMatcher.searchTitle("Hero Academy Season 2")).toBe("Hero Academy");
    expect(MalMatcher.searchTitle("Hero Academy 2nd Season")).toBe("Hero Academy");
    expect(MalMatcher.titleScore("Hero Academy", s1)).toBeGreaterThanOrEqual(STRONG_TITLE);
    expect(MalMatcher.titleScore("Hero Academy", other)).toBeLessThan(STRONG_TITLE);
  });
});

/** Seasons and episodes worked out without asking: short names, typos, recaps, branches, airing seasons and parts. */
describe("MalMatcher seasons", () => {
  class Catalog implements MalCatalog {
    readonly all: Map<number, MalAnime>;
    watchingList: [MalAnime, MalListStatus][] = [];
    completedList: [MalAnime, MalListStatus][] = [];
    readonly queries: string[] = [];
    constructor(
      entries: MalAnime[],
      private readonly results: (query: string) => number[],
    ) {
      this.all = new Map(entries.map((a) => [a.id, a]));
    }
    async search(query: string) {
      this.queries.push(query);
      return this.results(query).map((id) => this.all.get(id)!);
    }
    async anime(id: number) {
      const a = this.all.get(id);
      if (!a) throw new Error(`no anime ${id}`);
      return a;
    }
    async watching() {
      return this.watchingList;
    }
    async completed() {
      return this.completedList;
    }
  }

  const match = (catalog: MalCatalog, name: string, season: number | null, episode: number, saved: MalMapping | null = null) =>
    new MalMatcher(catalog).match({ title: name, season, episode, seriesKey: name }, saved);
  const at = async (catalog: MalCatalog, name: string, season: number | null, episode: number, saved: MalMapping | null = null) => {
    const m = (await match(catalog, name, season, episode, saved))!;
    return [m.anime.id, m.episode];
  };

  const anime = (
    id: number,
    title: string,
    episodes: number,
    o: { prequels?: number[]; sequels?: number[]; type?: string; en?: string; related?: [number, string][] } = {},
  ) =>
    malAnime({
      id,
      title,
      mediaType: o.type ?? "tv",
      numEpisodes: episodes,
      alternativeTitles: o.en ? { synonyms: [], en: o.en, ja: null } : null,
      relatedAnime: [
        ...(o.prequels ?? []).map((p) => ({ node: malAnime({ id: p, title: "p" }), relationType: "prequel" })),
        ...(o.sequels ?? []).map((s) => ({ node: malAnime({ id: s, title: "s" }), relationType: "sequel" })),
        ...(o.related ?? []).map(([rid, kind]) => ({ node: malAnime({ id: rid, title: "r" }), relationType: kind })),
      ],
    });

  // --- Tensei Kizoku: a long MyAnimeList title, 12 + 12 + a season still airing --------------------------------

  const tk1 = anime(1, "Tensei Kizoku, Kantei Skill de Nariagaru", 12, {
    sequels: [2],
    en: "As a Reincarnated Aristocrat, I'll Use My Appraisal Skill to Rise in the World",
  });
  const tk2 = anime(2, "Tensei Kizoku, Kantei Skill de Nariagaru 2nd Season", 12, { prequels: [1], sequels: [3] });
  const tk3 = anime(3, "Tensei Kizoku, Kantei Skill de Nariagaru 3rd Season", 0, { prequels: [2] });
  const tensei = () => new Catalog([tk1, tk2, tk3], (q) => (q.toLowerCase().includes("kizoku") || q.toLowerCase().includes("tens") ? [2, 1, 3] : []));

  it("episode 27 of a short name is the third season's third episode, without asking", async () => {
    const m = (await match(tensei(), "Tensei Kizoku", 1, 27))!;
    expect([m.anime.id, m.episode]).toEqual([3, 3]);
    expect(m.sure).toBe(true);
    expect(await at(tensei(), "Tensei Kizoku", null, 27)).toEqual([3, 3]);
  });

  it("a typo in the name still matches", async () => {
    const m = (await match(tensei(), "Tensai Kizoku", null, 27))!;
    expect([m.anime.id, m.episode]).toEqual([3, 3]);
    expect(m.sure).toBe(true);
    expect(MalMatcher.titleScore("Tensai Kizoku", tk1)).toBeGreaterThanOrEqual(STRONG_TITLE);
  });

  it("a saved match maps the episodes before it too", async () => {
    // The user matched episode 27 to the third season's episode 3.
    const saved = { malId: 3, offset: 24 };
    expect(await at(tensei(), "Whatever", null, 5, saved)).toEqual([1, 5]);
    expect(await at(tensei(), "Whatever", null, 15, saved)).toEqual([2, 3]);
    expect(await at(tensei(), "Whatever", null, 28, saved)).toEqual([3, 4]);
  });

  it("when the name matches poorly, shorter searches find the show", async () => {
    const catalog = new Catalog([tk1, tk2, tk3], (q) => (q === "Tensei Kizoku Kantei" ? [1, 2, 3] : []));
    expect(await at(catalog, "Tensei Kizoku Kantei Skill Something Long", null, 13)).toEqual([2, 1]);
    expect(catalog.queries).toContain("Tensei Kizoku Kantei");
  });

  // --- Recaps, movies and branches ---------------------------------------------------------------------------

  it("recaps and movies between seasons don't shift the count", async () => {
    const s1 = anime(10, "Blue Hour", 12, { sequels: [11] });
    const recap = anime(11, "Blue Hour: Recap", 1, { prequels: [10], sequels: [12], type: "ona" });
    const movie = anime(12, "Blue Hour Movie", 1, { prequels: [11], sequels: [13], type: "movie" });
    const s2 = anime(13, "Blue Hour 2nd Season", 12, { prequels: [12] });
    const catalog = new Catalog([s1, recap, movie, s2], () => [10, 13]);
    expect(await at(catalog, "Blue Hour", null, 15)).toEqual([13, 3]);
    expect(await at(catalog, "Blue Hour", 2, 3)).toEqual([13, 3]);
  });

  it("with two sequels, the next season is followed rather than the movie", async () => {
    const s1 = anime(20, "Night Garden", 12, { sequels: [21, 22] });
    const movie = anime(21, "Night Garden: The Movie", 1, { prequels: [20], type: "movie" });
    const s2 = anime(22, "Night Garden Season 2", 12, { prequels: [20] });
    const catalog = new Catalog([s1, movie, s2], () => [20]);
    const m = (await match(catalog, "Night Garden", null, 15))!;
    expect([m.anime.id, m.episode]).toEqual([22, 3]);
    expect(m.sure).toBe(true);
  });

  // --- Attack on Titan: seasons in parts ---------------------------------------------------------------------

  const aot = [
    anime(30, "Shingeki no Kyojin", 25, { sequels: [31], en: "Attack on Titan", related: [[40, "side_story"]] }),
    anime(31, "Shingeki no Kyojin Season 2", 12, { prequels: [30], sequels: [32], en: "Attack on Titan Season 2" }),
    anime(32, "Shingeki no Kyojin Season 3", 12, { prequels: [31], sequels: [33], en: "Attack on Titan Season 3", related: [[41, "side_story"]] }),
    anime(33, "Shingeki no Kyojin Season 3 Part 2", 10, { prequels: [32], sequels: [34], en: "Attack on Titan Season 3 Part 2" }),
    anime(34, "Shingeki no Kyojin: The Final Season", 16, { prequels: [33], sequels: [35], en: "Attack on Titan: Final Season" }),
    anime(35, "Shingeki no Kyojin: The Final Season Part 2", 12, { prequels: [34], sequels: [36], en: "Attack on Titan: Final Season Part 2" }),
    anime(36, "Shingeki no Kyojin: The Final Season - Kanketsu-hen", 2, { prequels: [35], type: "special" }),
    anime(40, "Shingeki no Kyojin OVA", 8, { type: "ova" }),
    anime(41, "Shingeki no Kyojin Movie 3", 1, { type: "movie" }),
  ];
  const titan = () => new Catalog(aot, () => [33, 30, 34, 40]);

  it("a library season made of parts counts through them", async () => {
    const s3e15 = (await match(titan(), "Attack on Titan", 3, 15))!;
    expect([s3e15.anime.id, s3e15.episode]).toEqual([33, 3]);
    expect(s3e15.sure).toBe(true);
    const s4e20 = (await match(titan(), "Attack on Titan", 4, 20))!;
    expect([s4e20.anime.id, s4e20.episode]).toEqual([35, 4]);
    expect(s4e20.sure).toBe(true);
    expect(await at(titan(), "Attack on Titan", 3, 5)).toEqual([32, 5]);
  });

  it("absolute numbering counts through every part", async () => {
    // 25 + 12 + 12 + 10 = 59.
    const m = (await match(titan(), "Attack on Titan", null, 60))!;
    expect([m.anime.id, m.episode]).toEqual([34, 1]);
    expect(m.sure).toBe(true);
    expect(MalMatcher.groups(aot.filter(isMainSeries)).map((g) => g.map((a) => a.id))).toEqual([[30], [31], [32, 33], [34, 35]]);
  });

  // --- Unknown lengths and the user's list -----------------------------------------------------------------------

  const g1 = anime(50, "Star Drift", 12, { sequels: [51] });
  const g2 = anime(51, "Star Drift 2nd Season", 0, { prequels: [50], sequels: [52] });
  const g3 = anime(52, "Star Drift 3rd Season", 12, { prequels: [51] });
  const drift = () => new Catalog([g1, g2, g3], () => [50]);

  it("an unknown length that isn't the last season makes it ask", async () => {
    expect((await match(drift(), "Star Drift", null, 15))!.sure).toBe(false);
  });

  it("the user's list settles what the lengths can't", async () => {
    const catalog = drift();
    catalog.watchingList = [[g2, listStatus("watching", 2)]];
    const m = (await match(catalog, "Star Drift", null, 15))!;
    expect([m.anime.id, m.episode]).toEqual([51, 3]);
    expect(m.sure).toBe(true);
  });

  it("a franchise on the user's list is trusted even with a weak name", async () => {
    const catalog = new Catalog([tk1, tk2, tk3], () => [1]);
    expect((await match(catalog, "Appraisal Aristocrat Story", null, 3))!.sure).toBe(false);
    catalog.completedList = [[tk1, listStatus("completed", 12)]];
    expect((await match(catalog, "Appraisal Aristocrat Story", null, 3))!.sure).toBe(true);
  });
});
