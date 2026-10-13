import { describe, expect, it } from "vitest";
import type { LocalFile } from "../local/library";
import { find } from "../local/library";
import { FakeMediaSource, JF, JF2, PX } from "./fakeMediaSource.testutil";
import { MediaLocator, type LocalFiles, type RoomLocation } from "./locator";
import type { MediaSource } from "./mediaSource";

// Ported from MediaLocatorTest.kt; the relay step (which the Kotlin test leaves to other tests) is covered here too.
const movie = "North Wind (2022).mkv";

function setup(localNames: string[] = [], relayed: Record<string, string> = {}) {
  const files: LocalFile[] = localNames.map((name) => ({ name, size: 10, directory: "", folder: "Movies", open: async () => new File([], name) }));
  const local: LocalFiles = { find: (name) => find(files, name) };
  let servers: MediaSource[] = [];
  const locator = new MediaLocator<string>(local, () => servers, (name) => relayed[name] ?? null);
  return {
    locator,
    setServers: (list: MediaSource[]) => {
      servers = list;
    },
    locate: (name = movie) => locator.locateForRoom(name),
  };
}

function server(location: RoomLocation<string>) {
  if (location.kind !== "server") throw new Error(`Expected a server, got ${JSON.stringify(location)}`);
  return location;
}

describe("MediaLocator", () => {
  it("local media folders beat every server", async () => {
    const { setServers, locate } = setup([movie]);
    const jellyfin = new FakeMediaSource(JF, { exact: { [movie]: 10 } });
    setServers([jellyfin]);
    const found = await locate();
    expect(found.kind).toBe("local");
    expect(found.kind === "local" && found.file.name).toBe(movie);
    expect(jellyfin.exactLookups).toBe(0);
  });

  it("the preferred server streams when both have the exact file, and both get reports", async () => {
    const { locator, setServers, locate } = setup();
    setServers([new FakeMediaSource(JF, { exact: { [movie]: 10 } }), new FakeMediaSource(PX, { exact: { [movie]: 10 } })]);
    const viaJellyfin = server(await locate());
    expect(viaJellyfin.playable.sourceKey).toBe(JF);
    expect(new Set(viaJellyfin.copies.map((c) => c.sourceKey))).toEqual(new Set([JF, PX]));

    locator.preferredServer = PX;
    const viaPlex = server(await locate());
    expect(viaPlex.playable.sourceKey).toBe(PX);
    expect(viaPlex.matchedBy).toBe("EXACT_FILENAME");
  });

  it("two servers of one kind both get copies, and preferring the second streams from it", async () => {
    const { locator, setServers, locate } = setup();
    setServers([new FakeMediaSource(JF, { exact: { [movie]: 10 } }), new FakeMediaSource(JF2, { exact: { [movie]: 10 } })]);
    const first = server(await locate());
    expect(first.playable.sourceKey).toBe(JF);
    expect(first.copies).toEqual([
      { sourceKey: JF, itemId: `${JF}:${movie}` },
      { sourceKey: JF2, itemId: `${JF2}:${movie}` },
    ]);

    locator.preferredServer = JF2;
    const second = server(await locate());
    expect(second.playable.sourceKey).toBe(JF2);
    expect(new Set(second.copies.map((c) => c.sourceKey))).toEqual(new Set([JF, JF2]));
  });

  it("an exact match on any server beats a loose match on the preferred one", async () => {
    const { setServers, locate } = setup();
    const jellyfin = new FakeMediaSource(JF, { loose: { [movie]: "North Wind.mkv" } });
    const plex = new FakeMediaSource(PX, { exact: { [movie]: 10 } });
    setServers([jellyfin, plex]);
    const found = server(await locate());
    expect(found.playable.sourceKey).toBe(PX);
    expect(found.copies).toEqual([{ sourceKey: PX, itemId: `${PX}:${movie}` }]);
    expect(jellyfin.looseLookups).toBe(0);
  });

  it("the loose pass runs only without an exact match, preferred server first", async () => {
    const { locator, setServers, locate } = setup();
    const jellyfin = new FakeMediaSource(JF, { loose: { [movie]: "North Wind.mkv" } });
    const plex = new FakeMediaSource(PX, { loose: { [movie]: "North.Wind.2022.mkv" } });
    setServers([plex, jellyfin]);
    locator.preferredServer = JF;
    const found = server(await locate());
    expect(found.playable.sourceKey).toBe(JF);
    expect(found.matchedBy).toBe("PARSED_SEARCH");
    expect(found.copies).toEqual([{ sourceKey: JF, itemId: `${JF}:North Wind.mkv` }]);
    expect(plex.looseLookups).toBe(0);
  });

  it("copies need the same size when both sizes are known", async () => {
    const { setServers, locate } = setup();
    const jellyfin = new FakeMediaSource(JF, { exact: { [movie]: 10 } });
    setServers([jellyfin, new FakeMediaSource(PX, { exact: { [movie]: 99 } })]);
    expect(server(await locate()).copies.map((c) => c.sourceKey)).toEqual([JF]);

    setServers([jellyfin, new FakeMediaSource(PX, { exact: { [movie]: 0 } })]);
    expect(server(await locate()).copies).toHaveLength(2);
  });

  it("copies of an explicit pick are found on the other servers only", async () => {
    const { locator, setServers } = setup();
    const jellyfin = new FakeMediaSource(JF, { exact: { [movie]: 10 } });
    const plex = new FakeMediaSource(PX, { exact: { [movie]: 10 } });
    setServers([jellyfin, plex]);
    expect(await locator.copiesOf(plex.playableFor(movie))).toEqual([
      { sourceKey: PX, itemId: `${PX}:${movie}` },
      { sourceKey: JF, itemId: `${JF}:${movie}` },
    ]);
    expect(plex.exactLookups).toBe(0);
  });

  it("servers are searched at the same time", async () => {
    const { setServers, locate } = setup();
    setServers([
      new FakeMediaSource(JF, { exact: { [movie]: 10 }, lookupDelayMs: 200 }),
      new FakeMediaSource(PX, { exact: { [movie]: 10 }, lookupDelayMs: 200 }),
    ]);
    const started = performance.now();
    await locate();
    expect(performance.now() - started).toBeLessThan(350);
  });

  it("missing everywhere explains why", async () => {
    const { setServers, locate } = setup();
    expect(await locate()).toEqual({ kind: "missing", reason: "Not in your media folders" });
    setServers([new FakeMediaSource(JF), new FakeMediaSource(PX)]);
    expect(await locate()).toEqual({ kind: "missing", reason: "Not in Living; Not in Den" });
  });

  it("another viewer's copy through the relay comes last", async () => {
    const { locator, setServers, locate } = setup([], { [movie]: "relay-17" });
    expect(await locate()).toEqual({ kind: "relay", file: "relay-17" });
    setServers([new FakeMediaSource(JF)]);
    expect(await locate()).toEqual({ kind: "relay", file: "relay-17" });
    setServers([new FakeMediaSource(JF, { exact: { [movie]: 10 } })]);
    expect((await locate()).kind).toBe("server");
    expect(locator.hasRelay(movie)).toBe(true);
    expect(locator.hasRelay("Other.mkv")).toBe(false);
  });
});
