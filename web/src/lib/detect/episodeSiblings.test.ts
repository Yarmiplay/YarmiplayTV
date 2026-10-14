import { describe, expect, it } from "vitest";
import { EpisodeSiblings, type EpisodeInfo, type LocalVideo, type PlayingFile, type ServerEpisode } from "./episodeSiblings";
import { keyOf } from "./fingerprintCache";

// Ported from EpisodeSiblingsTest.kt: the other episodes of the playing one's season, among the files on this device;
// and, beyond it, from a server's season.

/** A stand-in for episodeFromFile, for the names these tests use: "[Group] Show - 02.mkv" and "Show S01E05.mkv". */
function episodeFromFile(name: string): EpisodeInfo | null {
  const sxe = /^(.*?)\s+S(\d+)E(\d+)\.\w+$/i.exec(name);
  if (sxe) return { seriesKey: "file/" + sxe[1].toLowerCase(), season: Number(sxe[2]), episode: Number(sxe[3]) };
  const dash = /^(?:\[[^\]]*\]\s*)?(.*?)\s+-\s+(\d+)\.\w+$/.exec(name);
  if (dash) return { seriesKey: "file/" + dash[1].toLowerCase(), season: null, episode: Number(dash[2]) };
  return null;
}

function video(folder: string, path: string): LocalVideo {
  const slash = path.lastIndexOf("/");
  return {
    name: path.slice(slash + 1),
    size: 10,
    directory: slash < 0 ? "" : path.slice(0, slash),
    folder,
    open: async () => new Blob([new Uint8Array(10)]),
  };
}

const playing = (file: LocalVideo): PlayingFile => ({ episode: episodeFromFile(file.name), local: file, copies: [] });

describe("EpisodeSiblings", () => {
  it("the same show and season in the media folder, the same folder first, nearest first", async () => {
    const mine = video("Anime", "Show/[Group] Show - 02.mkv");
    const files = [
      mine,
      ...[1, 3, 4, 6].map((n) => video("Anime", `Show/[Group] Show - 0${n}.mkv`)),
      video("Anime", "Show/[Group] Other Show - 03.mkv"),
      // Another release of the same show elsewhere: only used when the folder has nothing.
      video("Anime", "Old/[Old] Show - 05.mkv"),
    ];
    const found = (await new EpisodeSiblings(() => files, episodeFromFile).of(playing(mine)))!;
    expect(found.number).toBe(2);
    expect(found.others.map((e) => e.number)).toEqual([3, 1, 4, 6]);
    expect(found.others[0].remote).toBe(false);
    expect(found.others[0].key).toBe(keyOf("[Group] Show - 03.mkv", 10, ""));
  });

  it("files opened together find each other", async () => {
    // The browser can't look beside an opened file; the files dropped with it are among the page's files.
    const mine = video("", "Show S01E05.mkv");
    const files = [mine, video("", "Show S01E06.mkv"), video("", "Show S02E01.mkv")];
    const found = (await new EpisodeSiblings(() => files, episodeFromFile).of(playing(mine)))!;
    expect(found.others.map((e) => e.number)).toEqual([6]);
  });

  it("no episode number, no season", async () => {
    const mine = video("", "A Film (2020).mkv");
    expect(await new EpisodeSiblings(() => [mine], episodeFromFile).of(playing(mine))).toBeNull();
  });

  it("a server's episode finds its season on the server", async () => {
    const episode = (itemId: string, n: number): ServerEpisode => ({
      itemId,
      url: `http://server/${itemId}`,
      fileName: `Show - ${n}.mkv`,
      sizeBytes: 1000 + n,
      durationSeconds: n === 3 ? 0 : 1440,
      episode: { seriesKey: "series1", season: 1, episode: n },
      open: () => Promise.reject(new Error("not read here")),
    });
    const season = [episode("i1", 1), episode("i2", 2), episode("i3", 3), episode("i3b", 3), episode("i4", 4)];
    const failing = { itemId: "x", serverName: "Down", seasonEpisodes: () => Promise.reject(new Error("offline")) };
    const working = { itemId: "i2", serverName: "Jellyfin", seasonEpisodes: async () => season };
    const found = (await new EpisodeSiblings(() => [], episodeFromFile).of({ episode: null, local: null, copies: [failing, working] }))!;
    expect(found.number).toBe(2);
    expect(found.others.map((e) => [e.number, e.remote, e.duration])).toEqual([
      [3, true, null],
      [1, true, 1440],
      [4, true, 1440],
    ]);
  });
});
