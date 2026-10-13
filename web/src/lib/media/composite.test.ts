import { describe, expect, it } from "vitest";
import { CompositeMediaSource } from "./composite";
import {
  isPlayable,
  MediaSourceError,
  mediaItem,
  notFound,
  uniqueKey,
  type Found,
  type MediaItem,
  type MediaSource,
  type PlayableMedia,
  type ResolveResult,
} from "./mediaSource";
import type { SkipSegment } from "./skipSegment";

// Ported from CompositeMediaSourceTest.kt.
class FakeSource implements MediaSource {
  readonly kind: string;
  readonly displayName: string;
  readonly childrenOf: string[] = [];

  constructor(
    readonly key: string,
    private readonly items: MediaItem[],
    private readonly failing = false,
  ) {
    this.kind = key.split(":")[0];
    this.displayName = key.charAt(0).toUpperCase() + key.slice(1);
  }

  private check() {
    if (this.failing) throw new MediaSourceError(`${this.key} is down`);
  }

  async libraries() {
    this.check();
    return this.items.filter((i) => i.type === "LIBRARY");
  }
  async children(parent: MediaItem) {
    this.childrenOf.push(parent.id);
    return [];
  }
  async search() {
    this.check();
    return this.items.filter(isPlayable);
  }
  async recent() {
    this.check();
    return this.items.filter(isPlayable);
  }
  async playable(item: MediaItem): Promise<PlayableMedia> {
    return {
      itemId: item.id,
      url: `http://${this.key}/${item.id}`,
      fileName: `${item.id}.mkv`,
      sizeBytes: 1,
      durationSeconds: 1,
      title: item.name,
      sourceKey: this.key,
      episode: null,
    };
  }
  async resolveByFilename(fileName: string): Promise<ResolveResult> {
    return notFound(fileName, "no");
  }
  async findExact(): Promise<Found | null> {
    return null;
  }
  async reportPlayback() {}
  async skipSegments(): Promise<SkipSegment[]> {
    return [];
  }
  async seasonEpisodes(): Promise<PlayableMedia[]> {
    return [];
  }
  imageUrl(item: MediaItem) {
    return `http://${this.key}/img/${item.id}`;
  }
}

const lib = (key: string, id: string) => mediaItem({ id, name: `${key} lib`, type: "LIBRARY", isFolder: true, sourceKey: key });
const movie = (key: string, id: string) => mediaItem({ id, name: `${key} ${id}`, type: "MOVIE", isFolder: false, sourceKey: key });

describe("CompositeMediaSource", () => {
  const setup = () => {
    const jellyfin = new FakeSource("jellyfin", [lib("jellyfin", "1"), movie("jellyfin", "a"), movie("jellyfin", "b")]);
    const plex = new FakeSource("plex", [lib("plex", "1"), movie("plex", "a")]);
    return { jellyfin, plex, composite: new CompositeMediaSource([jellyfin, plex]) };
  };

  it("libraries from both servers stay distinct", async () => {
    const { composite } = setup();
    const libs = await composite.libraries();
    expect(libs.map(uniqueKey)).toEqual(["jellyfin/1", "plex/1"]);
    expect(composite.sourceOf(libs[1])?.displayName).toBe("Plex");
  });

  it("recent and search interleave the servers", async () => {
    const { composite } = setup();
    expect((await composite.recent(10)).map(uniqueKey)).toEqual(["jellyfin/a", "plex/a", "jellyfin/b"]);
    expect((await composite.search("x", 2)).map(uniqueKey)).toEqual(["jellyfin/a", "plex/a"]);
  });

  it("the same file on both servers is listed once", async () => {
    const withFile = (m: MediaItem, file: string) => ({ ...m, path: `/media/${file}` });
    const shared = new CompositeMediaSource([
      new FakeSource("jellyfin", [withFile(movie("jellyfin", "a"), "Paper Boats.mkv"), withFile(movie("jellyfin", "b"), "Other.mkv")]),
      new FakeSource("plex", [withFile(movie("plex", "x"), "paper boats.MKV"), movie("plex", "y")]),
    ]);
    expect((await shared.recent(10)).map(uniqueKey)).toEqual(["jellyfin/a", "jellyfin/b", "plex/y"]);
  });

  it("routes item calls to their own server", async () => {
    const { composite, jellyfin, plex } = setup();
    const plexMovie = movie("plex", "a");
    expect((await composite.playable(plexMovie)).url).toBe("http://plex/a");
    expect(composite.imageUrl(plexMovie)).toBe("http://plex/img/a");
    await composite.children(lib("plex", "1"));
    expect(plex.childrenOf).toEqual(["1"]);
    expect(jellyfin.childrenOf).toEqual([]);
    expect(composite.imageUrl(movie("gone", "x"))).toBeNull();
  });

  it("two servers of one kind keep their items apart", async () => {
    const den = new FakeSource("plex:den", [lib("plex:den", "1"), movie("plex:den", "7")]);
    const office = new FakeSource("plex:office", [lib("plex:office", "1"), movie("plex:office", "7")]);
    const both = new CompositeMediaSource([den, office]);

    const libs = await both.libraries();
    expect(libs.map(uniqueKey)).toEqual(["plex:den/1", "plex:office/1"]);
    expect((await both.playable(movie("plex:office", "7"))).url).toBe("http://plex:office/7");
    expect(both.imageUrl(movie("plex:den", "7"))).toBe("http://plex:den/img/7");
    await both.children(libs[1]);
    expect(office.childrenOf).toEqual(["1"]);
    expect(den.childrenOf).toEqual([]);
  });

  it("skips a failing server", async () => {
    const { plex } = setup();
    const down = new CompositeMediaSource([new FakeSource("jellyfin", [lib("jellyfin", "1")], true), plex]);
    expect((await down.libraries()).map(uniqueKey)).toEqual(["plex/1"]);
    expect((await down.recent(10)).map(uniqueKey)).toEqual(["plex/a"]);
  });

  it("fails when every server fails", async () => {
    await expect(new CompositeMediaSource([new FakeSource("plex", [], true)]).libraries()).rejects.toBeInstanceOf(MediaSourceError);
  });
});
