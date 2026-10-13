import { describe, expect, it } from "vitest";
import { fakeServer, type Answer, type Recorded } from "../fakeServer.testutil";
import { clientInfo } from "../http";
import { PlexClient, type PlexServer, type PlexSession } from "./client";
import type { PlexResourceConnection } from "./dtos";
import { PlexSource } from "./source";

// Ported from PlexSourceTest.kt (MockWebServer becomes a fake fetch; plex.tv is the same fake server).
const BASE = "http://plex.test:32400";
const DEAD = "http://127.0.0.1:1";
const fileName = "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv";
const path = `/media/Neptunia/Season 1/${fileName}`;
const info = clientInfo({ deviceName: "Test TV", deviceId: "dev1", version: "0.1" });

const episodeJson = () =>
  JSON.stringify({
    ratingKey: "ep1",
    type: "episode",
    title: "The Goddess (Neptune) Of Planeptune",
    index: 1,
    parentIndex: 1,
    parentRatingKey: "season1",
    grandparentTitle: "Hyperdimension Neptunia",
    grandparentRatingKey: "show1",
    duration: 1420000,
    viewOffset: 60000,
    Media: [{ duration: 1420000, Part: [{ key: "/library/parts/7/1700000000/file.mkv", file: path, size: 587443083 }] }],
  });

const session = (over: Partial<PlexSession> = {}): PlexSession => ({
  serverUrl: BASE,
  serverName: "Benny",
  machineId: "mid",
  userName: "yarmi",
  serverToken: "srvtok",
  accountToken: "acct",
  ...over,
});

function setup(opts: { pageProtocol?: string; resources?: string; plexTvUrl?: string } = {}) {
  let pinPolls = 0;
  const state = { revoked: false };
  const server = fakeServer((req: Recorded): Answer => {
    const url = req.url;
    if (url.origin === DEAD) return "unreachable";
    if (state.revoked && url.pathname === "/identity") return { status: 401 };
    if (url.pathname === "/notplex/identity") return { status: 200, body: "<html>Router login</html>", contentType: "text/html" };
    switch (url.pathname) {
      case "/api/v2/pins":
        return `{"id":42,"code":"ABCD","expiresIn":900}`;
      case "/api/v2/pins/42":
        return ++pinPolls >= 2 ? `{"id":42,"code":"ABCD","authToken":"acct"}` : `{"id":42,"code":"ABCD"}`;
      case "/api/v2/user":
        return `{"username":"yarmi"}`;
      case "/api/v2/resources":
        return (
          opts.resources ??
          JSON.stringify([
            { name: "Player", provides: "player", clientIdentifier: "p1" },
            {
              name: "Benny",
              provides: "server",
              clientIdentifier: "mid",
              accessToken: "srvtok",
              owned: true,
              connections: [
                { uri: DEAD, local: true },
                { uri: BASE, local: false },
              ],
            },
          ])
        );
      case "/identity":
        return `{"MediaContainer":{"machineIdentifier":"mid"}}`;
      case "/library/sections":
        return `{"MediaContainer":{"Directory":[
          {"key":"1","title":"Anime","type":"show"},
          {"key":"2","title":"Movies","type":"movie"},
          {"key":"3","title":"Music","type":"artist"}]}}`;
      case "/library/sections/1/all":
        return url.searchParams.get("type") === "4"
          ? `{"MediaContainer":{"totalSize":1,"Metadata":[${episodeJson()}]}}`
          : `{"MediaContainer":{"Metadata":[{"ratingKey":"show1","type":"show","title":"Hyperdimension Neptunia","leafCount":12,"viewedLeafCount":12}]}}`;
      case "/library/sections/2/all":
        return `{"MediaContainer":{"totalSize":1,"Metadata":[
          {"ratingKey":"m1","type":"movie","title":"Other","year":2020,"Media":[{"Part":[{"key":"/library/parts/9/1/file.mkv","file":"/media/Other (2020).mkv","size":5}]}]}]}}`;
      case "/library/metadata/ep1":
        return url.searchParams.get("includeMarkers") === "1"
          ? `{"MediaContainer":{"Metadata":[{"ratingKey":"ep1","type":"episode","Marker":[
              {"type":"intro","startTimeOffset":5000,"endTimeOffset":95000},
              {"type":"commercial","startTimeOffset":600000,"endTimeOffset":660000},
              {"type":"credits","startTimeOffset":1350000,"endTimeOffset":1440000}]}]}}`
          : `{"MediaContainer":{"Metadata":[${episodeJson()}]}}`;
      case "/library/metadata/season1/children":
        return `{"MediaContainer":{"Metadata":[${episodeJson()},
          {"ratingKey":"ep2","type":"episode","title":"Two","index":2,"parentIndex":1,"grandparentTitle":"Hyperdimension Neptunia",
           "Media":[{"duration":1430000,"Part":[{"key":"/library/parts/8/1/file.mkv","file":"/media/Neptunia/Season 1/E02.mkv","size":5}]}]},
          {"ratingKey":"ep3","type":"episode","title":"No file","index":3}]}}`;
      case "/library/metadata/show1/allLeaves":
        return `{"MediaContainer":{"Metadata":[${episodeJson()}]}}`;
      case "/hubs/search":
        return `{"MediaContainer":{"Hub":[{"type":"show","Metadata":[{"ratingKey":"show1","type":"show","title":"Hyperdimension Neptunia"}]}]}}`;
      case "/library/onDeck":
        return `{"MediaContainer":{"Metadata":[${episodeJson()}]}}`;
      case "/library/recentlyAdded":
        return `{"MediaContainer":{"Metadata":[${episodeJson()},{"ratingKey":"a1","type":"album","title":"Music"}]}}`;
      case "/:/timeline":
      case "/:/scrobble":
        return "";
      default:
        return { status: 404 };
    }
  });
  const client = new PlexClient(info, { fetch: server.fetch, plexTvUrl: opts.plexTvUrl ?? BASE, pageProtocol: opts.pageProtocol ?? "http:" });
  return { server, client, state, source: new PlexSource(client, session()) };
}

describe("Plex", () => {
  it("intro and credits markers are the opening and ending", async () => {
    expect(await setup().source.skipSegments("ep1")).toEqual([
      { kind: "OPENING", start: 5, end: 95 },
      { kind: "ENDING", start: 1350, end: 1440 },
    ]);
  });

  it("season episodes are the season's playable episodes", async () => {
    const { source } = setup();
    const episodes = await source.seasonEpisodes("ep1");
    expect(episodes.map((e) => e.itemId)).toEqual(["ep1", "ep2"]);
    expect(episodes[1].durationSeconds).toBeCloseTo(1430, 3);
    expect(episodes[1].url).toContain("/library/parts/8/1/file.mkv");
    expect(episodes[1].url).toContain("X-Plex-Token=srvtok");
    await expect(source.seasonEpisodes("missing")).rejects.toThrow();
  });

  it("linking shows the code, then gives the account token", async () => {
    const { client, server } = setup();
    const codes: string[] = [];
    const linked = await client.link({ onCode: (c) => codes.push(c), pollIntervalMs: 1 });
    expect(codes).toEqual(["ABCD"]);
    expect(linked).toEqual({ accountToken: "acct", userName: "yarmi" });
    const pin = server.requests.find((r) => r.url.pathname === "/api/v2/pins");
    expect(pin?.method).toBe("POST");
    expect(pin?.url.searchParams.get("strong")).toBe("false");
    expect(pin?.headers.get("X-Plex-Client-Identifier")).toBe("dev1");
    expect(pin?.headers.get("X-Plex-Product")).toBe("YarmiplayTV");
    expect(pin?.headers.get("X-Plex-Version")).toBe("0.1");
    expect(pin?.headers.get("X-Plex-Device-Name")).toBe("Test TV");
    expect(pin?.headers.get("Accept")).toBe("application/json");
  });

  it("linking stops when cancelled", async () => {
    const { client } = setup();
    const cancel = new AbortController();
    const linking = client.link({ onCode: () => cancel.abort(new Error("cancelled")), pollIntervalMs: 50, signal: cancel.signal });
    await expect(linking).rejects.toThrow("cancelled");
  });

  it("lists only servers and connects to a reachable address", async () => {
    const { client, server } = setup();
    const servers = await client.servers("acct");
    expect(servers.map((s) => s.name)).toEqual(["Benny"]);
    const resources = server.requests.find((r) => r.url.pathname === "/api/v2/resources");
    expect(resources?.url.searchParams.get("includeHttps")).toBe("1");
    expect(resources?.headers.get("X-Plex-Token")).toBe("acct");
    const s = await client.connect(servers[0], "acct", "yarmi");
    expect(s.serverUrl).toBe(BASE);
    expect(s.serverToken).toBe("srvtok");
    expect(s.accountToken).toBe("acct");
  });

  it("connect skips addresses that aren't Plex and explains failures", async () => {
    const { client } = setup();
    const html: PlexResourceConnection = { uri: `${BASE}/notplex`, local: true };
    const dead: PlexResourceConnection = { uri: DEAD, local: true };
    const good: PlexResourceConnection = { uri: BASE };
    const server: PlexServer = { name: "Benny", machineId: "mid", accessToken: "srvtok", owned: true, connections: [html, dead, good] };
    expect((await client.connect(server, "acct", "yarmi")).serverUrl).toBe(BASE);

    const error = await client.connect({ ...server, connections: [html, dead] }, "acct", "yarmi").catch((e: unknown) => e);
    const lines = (error as Error).message.split("\n");
    expect(lines[0]).toBe("Can't reach Benny; is it online?");
    expect(lines[1].startsWith(`${html.uri}: not a Plex server`)).toBe(true);
    expect(lines[2].startsWith(`${dead.uri}: Cannot reach 127.0.0.1:1`)).toBe(true);
  });

  it("from an https page, plain-http addresses are passed over for the https plex.direct one", async () => {
    const direct = "https://10-0-0-5.abcdef.plex.direct:32400";
    const resources = JSON.stringify([
      {
        name: "Benny",
        provides: "server",
        clientIdentifier: "mid",
        accessToken: "srvtok",
        connections: [
          { uri: "http://10.0.0.5:32400", local: true, protocol: "http" },
          { uri: direct, local: true, protocol: "https" },
        ],
      },
    ]);
    const { client, server } = setup({ pageProtocol: "https:", resources, plexTvUrl: "https://plex.tv.test" });
    const [benny] = await client.servers("acct");
    expect((await client.connect(benny, "acct", "yarmi")).serverUrl).toBe(direct);
    expect(server.requests.some((r) => r.url.origin === "http://10.0.0.5:32400")).toBe(false);

    const error = await client.connect({ ...benny, connections: [benny.connections[0]] }, "acct", "yarmi").catch((e: unknown) => e);
    expect((error as Error).message.split("\n")[1]).toContain("over plain http from a page served over HTTPS");
  });

  it("refresh keeps a working session and drops a revoked one", async () => {
    const { client, state } = setup();
    expect(await client.refresh(session())).toEqual(session());
    state.revoked = true;
    expect(await client.refresh(session())).toBeNull();
  });

  it("refresh finds the server at a new address", async () => {
    const { client } = setup();
    expect((await client.refresh(session({ serverUrl: DEAD })))?.serverUrl).toBe(BASE);
  });

  it("browses video libraries and their children", async () => {
    const { source } = setup();
    const libs = await source.libraries();
    expect(libs.map((l) => l.name)).toEqual(["Anime", "Movies"]);
    expect(libs[0].collectionType).toBe("tvshows");
    expect(source.key).toBe("plex:mid");
    expect(libs.every((l) => l.sourceKey === source.key)).toBe(true);
    const shows = await source.children(libs[0]);
    expect(shows).toHaveLength(1);
    expect(shows[0].type).toBe("SERIES");
    expect(shows[0].played).toBe(true);
  });

  it("recent keeps videos, once each", async () => {
    const recent = await setup().source.recent(10);
    expect(recent.map((r) => r.id)).toEqual(["ep1"]);
    expect(recent[0].seriesName).toBe("Hyperdimension Neptunia");
  });

  it("findExact builds a direct-play URL with the original name", async () => {
    const found = await setup().source.findExact(fileName);
    if (!found) throw new Error("not found");
    expect(found.matchedBy).toBe("EXACT_FILENAME");
    expect(found.playable.fileName).toBe(fileName);
    expect(found.playable.sizeBytes).toBe(587443083);
    expect(found.playable.durationSeconds).toBeCloseTo(1420, 3);
    expect(found.playable.sourceKey).toBe("plex:mid");
    expect(found.playable.url).toBe(`${BASE}/library/parts/7/1700000000/file.mkv?X-Plex-Token=srvtok`);
    expect(found.playable.episode).toMatchObject({ seriesName: "Hyperdimension Neptunia", season: 1, episode: 1, seriesKey: "plex:mid/show1" });
  });

  it("findExact ignores loose matches", async () => {
    const { source } = setup();
    expect(await source.findExact("hyperdimension_neptunia_-_s01e01_-_the_goddess_(neptune)_of_planeptune.mkv")).toBeNull();
    expect(await source.findExact("Something Else.mkv")).toBeNull();
  });

  it("resolves a normalized name, then a parsed one", async () => {
    const { source } = setup();
    const normalized = await source.resolveByFilename("hyperdimension_neptunia_-_s01e01_-_the_goddess_(neptune)_of_planeptune.mkv");
    expect(normalized.kind === "found" && normalized.matchedBy).toBe("NORMALIZED_FILENAME");
    const parsed = await source.resolveByFilename("[Group] Hyperdimension Neptunia S01E01 [1080p].mkv");
    if (parsed.kind !== "found") throw new Error(parsed.reason);
    expect(parsed.matchedBy).toBe("PARSED_SEARCH");
    expect(parsed.item.id).toBe("ep1");
    expect((await source.resolveByFilename("Something Else Entirely.mkv")).kind).toBe("notFound");
  });

  it("reports the timeline and scrobbles", async () => {
    const { source, server } = setup();
    const report = { itemId: "ep1", sessionId: "sess1", durationSeconds: 1420 };
    await source.reportPlayback({ ...report, state: "PAUSED", positionSeconds: 61.5, markWatched: false });
    await source.reportPlayback({ ...report, state: "PLAYING", positionSeconds: 1300, markWatched: true });
    const timelines = server.requests.filter((r) => r.url.pathname === "/:/timeline");
    expect(timelines.map((r) => r.url.searchParams.get("state"))).toEqual(["paused", "playing"]);
    expect(timelines[0].url.searchParams.get("time")).toBe("61500");
    expect(timelines[0].url.searchParams.get("duration")).toBe("1420000");
    expect(timelines[0].headers.get("X-Plex-Session-Identifier")).toBe("sess1");
    expect(timelines[0].headers.get("X-Plex-Token")).toBe("srvtok");
    const scrobbles = server.requests.filter((r) => r.url.pathname === "/:/scrobble");
    expect(scrobbles).toHaveLength(1);
    expect(scrobbles[0].url.searchParams.get("key")).toBe("ep1");
  });
});
