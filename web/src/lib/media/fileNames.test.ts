import { describe, expect, it } from "vitest";
import * as FileNames from "./fileNames";

// Ported from FileNamesTest.kt.
describe("FileNames", () => {
  it("baseName handles Windows and Unix paths", () => {
    expect(FileNames.baseName("F:\\Torrents\\Show\\a.mkv")).toBe("a.mkv");
    expect(FileNames.baseName("/media/tv/Show/a.mkv")).toBe("a.mkv");
    expect(FileNames.baseName("a.mkv")).toBe("a.mkv");
  });

  it("normalize matches Syncplay's stripping", () => {
    expect(FileNames.normalize("Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv")).toBe(
      FileNames.normalize("hyperdimension_neptunia_-_s01e01_-_the_goddess_neptune_of_planeptune.mkv"),
    );
    expect(FileNames.normalize("My%20File.mkv")).toBe(FileNames.normalize("My File.mkv"));
  });

  it("parses an episode", () => {
    const p = FileNames.parse("Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv");
    expect(p.title).toBe("Hyperdimension Neptunia");
    expect(p.season).toBe(1);
    expect(p.episode).toBe(1);
  });

  it("parses a release group's episode", () => {
    const p = FileNames.parse("[SubsPlease] Frieren.S01E12.1080p.HEVC.x265-Group.mkv");
    expect(p.title).toBe("Frieren");
    expect(p.season).toBe(1);
    expect(p.episode).toBe(12);
  });

  it("parses a movie with its year", () => {
    const p = FileNames.parse("Spirited.Away.2001.1080p.BluRay.x264.mkv");
    expect(p.title).toBe("Spirited Away");
    expect(p.year).toBe(2001);
    expect(p.episode).toBeNull();
  });

  it("parses a movie with its year in parentheses", () => {
    const p = FileNames.parse("Your Name (2016) [1080p].mkv");
    expect(p.title).toBe("Your Name");
    expect(p.year).toBe(2016);
  });

  it("finds episodes in anime release names", () => {
    const check = (name: string, title: string, season: number | null, episode: number, dir: string | null = null) => {
      const p = FileNames.parseEpisode(name, dir);
      expect(p, name).not.toBeNull();
      expect([p?.title, p?.season, p?.episode], name).toEqual([title, season, episode]);
    };
    check("[SubsPlease] Frieren - 05 (1080p) [ABCD1234].mkv", "Frieren", null, 5);
    check("[Group] Hero Academy S2 - 05v2 [1080p].mkv", "Hero Academy", 2, 5);
    check("Hero Academy 2nd Season - 13.mkv", "Hero Academy", 2, 13);
    check("Hero Academy Season 3 - 01.mkv", "Hero Academy", 3, 1);
    check("Hero.Academy.E50.1080p.mkv", "Hero Academy", null, 50);
    check("Show - S01E02 - Name.mkv", "Show", 1, 2);
    check("05.mkv", "Hero Academy", 2, 5, "Hero Academy/Season 2");
    check("Hero Academy - 07.mkv", "Hero Academy", 4, 7, "Anime/Hero Academy/Season 04");
  });

  it("movies aren't episodes", () => {
    expect(FileNames.parseEpisode("Your Name (2016) [1080p].mkv")).toBeNull();
    expect(FileNames.parseEpisode("Movie - 1080p.mkv")).toBeNull();
  });
});
