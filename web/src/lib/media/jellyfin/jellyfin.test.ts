import { describe, expect, it } from "vitest";
import { fakeServer, type Answer, type Recorded } from "../fakeServer.testutil";
import { clientInfo } from "../http";
import { MediaSourceError } from "../mediaSource";
import { JellyfinClient, jellyfinSession } from "./client";
import { JellyfinSource } from "./source";

// Ported from JellyfinSourceTest.kt (MockWebServer becomes a fake fetch; discovery over UDP has no browser version).
const BASE = "http://jf.test:8096";
const fileName = "Hyperdimension Neptunia - S01E01 - The Goddess (Neptune) Of Planeptune.mkv";
const path = `F:\\Torrents\\Neptunia\\Season 1\\${fileName}`;
const info = clientInfo({ deviceName: "Test TV", deviceId: "dev1", version: "0.1" });

function episodeJson(withSources: boolean): string {
  const sources = withSources
    ? [{ Id: "ms1", Path: path, Size: 587443083, RunTimeTicks: 14200000000, Protocol: "File" }]
    : undefined;
  return JSON.stringify({
    Id: "ep1",
    Name: "The Goddess (Neptune) Of Planeptune",
    Type: "Episode",
    IndexNumber: 1,
    ParentIndexNumber: 1,
    SeriesName: "Hyperdimension Neptunia",
    SeriesId: "series1",
    SeasonId: "season1",
    Path: path,
    RunTimeTicks: 14200000000,
    ...(sources ? { MediaSources: sources } : {}),
  });
}

function setup(opts: { pageProtocol?: string } = {}) {
  let quickConnectPolls = 0;
  const server = fakeServer((req: Recorded): Answer => {
    const q = (k: string) => req.url.searchParams.get(k);
    switch (req.url.pathname) {
      case "/System/Info/Public":
        return `{"ServerName":"Benny","Version":"10.11.6","Id":"srv"}`;
      case "/QuickConnect/Initiate":
        return `{"Secret":"s3cret","Code":"123456","Authenticated":false}`;
      case "/QuickConnect/Connect":
        return `{"Secret":"s3cret","Code":"123456","Authenticated":${++quickConnectPolls >= 2}}`;
      case "/Users/AuthenticateWithQuickConnect":
      case "/Users/AuthenticateByName":
        return `{"User":{"Id":"u1","Name":"yarmi"},"AccessToken":"tok","ServerId":"srv"}`;
      case "/Items":
        if (q("ids") === "ep1") return `{"Items":[${episodeJson(true)}],"TotalRecordCount":1}`;
        if (q("fields") === "Path" && q("recursive") === "true")
          return `{"Items":[${episodeJson(false)},{"Id":"m1","Name":"Other","Type":"Movie","Path":"/x/Other.mkv"}],"TotalRecordCount":2}`;
        if (q("includeItemTypes") === "Series")
          return `{"Items":[{"Id":"series1","Name":"Hyperdimension Neptunia","Type":"Series","IsFolder":true}],"TotalRecordCount":1}`;
        if (q("includeItemTypes") === "Episode") return `{"Items":[${episodeJson(true)}],"TotalRecordCount":1}`;
        return `{"Items":[],"TotalRecordCount":0}`;
      case "/Shows/series1/Episodes": {
        if (q("seasonId") !== "season1") return `{"Items":[]}`;
        const second = path.replace("E01", "E02");
        return JSON.stringify({
          Items: [
            JSON.parse(episodeJson(true)),
            {
              Id: "ep2",
              Name: "Two",
              Type: "Episode",
              IndexNumber: 2,
              ParentIndexNumber: 1,
              SeriesName: "Hyperdimension Neptunia",
              SeriesId: "series1",
              MediaSources: [{ Id: "ms2", Path: second, Size: 5, RunTimeTicks: 14300000000, Protocol: "File" }],
            },
            { Id: "ep3", Name: "No file", Type: "Episode", IndexNumber: 3 },
          ],
        });
      }
      case "/MediaSegments/ep1":
        return `{"Items":[
          {"Id":"s1","ItemId":"ep1","Type":"Recap","StartTicks":0,"EndTicks":300000000},
          {"Id":"s2","ItemId":"ep1","Type":"Intro","StartTicks":300000000,"EndTicks":1200000000},
          {"Id":"s3","ItemId":"ep1","Type":"Outro","StartTicks":13500000000,"EndTicks":14400000000}]}`;
      case "/Sessions/Playing":
      case "/Sessions/Playing/Progress":
      case "/Sessions/Playing/Stopped":
      case "/Users/u1/PlayedItems/ep1":
        return { status: 204 };
      default:
        return { status: 404 };
    }
  });
  const client = new JellyfinClient(info, { fetch: server.fetch, pageProtocol: opts.pageProtocol ?? "http:" });
  const session = jellyfinSession({ serverUrl: BASE, serverName: "Benny", serverId: "srv", userId: "u1", userName: "yarmi", accessToken: "tok" });
  return { server, client, session, source: new JellyfinSource(client, session) };
}

describe("Jellyfin", () => {
  it("media segments give the intro and outro", async () => {
    const { server, source } = setup();
    expect(await source.skipSegments("ep1")).toEqual([
      { kind: "OPENING", start: 30, end: 120 },
      { kind: "ENDING", start: 1350, end: 1440 },
    ]);
    const asked = server.requests.find((r) => r.url.pathname.startsWith("/MediaSegments"));
    expect(asked?.url.searchParams.getAll("includeSegmentTypes")).toEqual(["Intro", "Outro"]);
    // Servers before 10.10 have no media segments.
    expect(await source.skipSegments("old")).toEqual([]);
  });

  it("season episodes are the season's playable episodes", async () => {
    const { source } = setup();
    const episodes = await source.seasonEpisodes("ep1");
    expect(episodes.map((e) => e.itemId)).toEqual(["ep1", "ep2"]);
    expect(episodes.map((e) => e.episode?.episode)).toEqual([1, 2]);
    expect(episodes[1].durationSeconds).toBeCloseTo(1430, 3);
    expect(episodes[1].url).toContain("Videos/ep2/stream");
    expect(episodes[1].url).toContain("api_key=tok");
    expect(await setup().source.seasonEpisodes("missing")).toEqual([]);
  });

  it("Quick Connect shows the code, then gives the session", async () => {
    const { server, client } = setup();
    const codes: string[] = [];
    const session = await client.quickConnect(BASE, { onCode: (c) => codes.push(c), pollIntervalMs: 1 });
    expect(codes).toEqual(["123456"]);
    expect(session.accessToken).toBe("tok");
    expect(session.serverName).toBe("Benny");
    const initiate = server.requests.find((r) => r.url.pathname === "/QuickConnect/Initiate");
    expect(initiate?.method).toBe("POST");
    expect(initiate?.headers.get("Authorization")).toContain('DeviceId="dev1"');
    const auth = server.requests.find((r) => r.url.pathname === "/Users/AuthenticateWithQuickConnect");
    expect(JSON.parse(auth?.body ?? "{}")).toEqual({ Secret: "s3cret" });
  });

  it("Quick Connect stops polling when cancelled", async () => {
    const { client } = setup();
    const cancel = new AbortController();
    const waiting = client.quickConnect(BASE, { onCode: () => cancel.abort(new Error("cancelled")), pollIntervalMs: 50, signal: cancel.signal });
    await expect(waiting).rejects.toThrow("cancelled");
  });

  it("the authorization header names the app and device, and the token once signed in", () => {
    const { client } = setup();
    expect(client.authorizationHeader(null)).toBe('MediaBrowser Client="YarmiplayTV", Device="Test TV", DeviceId="dev1", Version="0.1"');
    expect(client.authorizationHeader("tok")).toBe('MediaBrowser Client="YarmiplayTV", Device="Test TV", DeviceId="dev1", Version="0.1", Token="tok"');
  });

  it("signs in by name, and a wrong password says so", async () => {
    const { client } = setup();
    const session = await client.login("jf.test", "yarmi", "pw");
    expect(session).toMatchObject({ serverUrl: BASE, serverId: "srv", userId: "u1", userName: "yarmi", accessToken: "tok", sharedBy: "" });
    const refusing = new JellyfinClient(info, {
      fetch: fakeServer((r) => (r.url.pathname === "/System/Info/Public" ? "{}" : { status: 401 })).fetch,
      pageProtocol: "http:",
    });
    await expect(refusing.login(BASE, "yarmi", "nope")).rejects.toThrow("Wrong username or password");
  });

  it("normalizes server URLs", () => {
    const { client } = setup();
    expect(client.normalizeServerUrl("10.0.2.2")).toBe("http://10.0.2.2:8096");
    expect(client.normalizeServerUrl("http://10.0.2.2:8096/")).toBe("http://10.0.2.2:8096");
    expect(client.normalizeServerUrl("https://jf.example.com")).toBe("https://jf.example.com");
    expect(client.normalizeServerUrl("host:9000")).toBe("http://host:9000");
    expect(() => client.normalizeServerUrl("  ")).toThrow("Enter a server address");
  });

  it("an https page refuses a plain-http server with a clear message", async () => {
    const { client, server } = setup({ pageProtocol: "https:" });
    const error = await client.login("10.0.0.5", "yarmi", "pw").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(MediaSourceError);
    expect((error as Error).message).toContain("served over HTTPS");
    expect((error as Error).message).toContain("http://10.0.0.5:8096");
    expect((error as Error).message).toContain("YarmiplayServerTV");
    expect(server.requests).toEqual([]);
    // The same server over https is tried.
    await client.login("https://jf.test", "yarmi", "pw");
    expect(server.requests.length).toBeGreaterThan(0);
  });

  it("resolves an exact file name and builds the stream URL", async () => {
    const { source } = setup();
    const result = await source.resolveByFilename(fileName);
    if (result.kind !== "found") throw new Error(result.reason);
    expect(result.matchedBy).toBe("EXACT_FILENAME");
    expect(result.playable.fileName).toBe(fileName);
    expect(result.playable.sizeBytes).toBe(587443083);
    expect(result.playable.durationSeconds).toBeCloseTo(1420, 3);
    expect(result.playable.url).toContain("/Videos/ep1/stream?static=true&mediaSourceId=ms1&api_key=tok");
    expect(result.playable.title).toBe("Hyperdimension Neptunia · S01E01 · The Goddess (Neptune) Of Planeptune");
  });

  it("resolves a normalized file name", async () => {
    const result = await setup().source.resolveByFilename("hyperdimension_neptunia_-_s01e01_-_the_goddess_(neptune)_of_planeptune.mkv");
    expect(result.kind === "found" && result.matchedBy).toBe("NORMALIZED_FILENAME");
  });

  it("falls back to a search by the parsed name", async () => {
    const result = await setup().source.resolveByFilename("[Group] Hyperdimension Neptunia S01E01 [1080p].mkv");
    if (result.kind !== "found") throw new Error(result.reason);
    expect(result.matchedBy).toBe("PARSED_SEARCH");
    expect(result.item.id).toBe("ep1");
  });

  it("reports not found", async () => {
    expect((await setup().source.resolveByFilename("Something Else Entirely.mkv")).kind).toBe("notFound");
  });

  it("findExact only accepts the exact name", async () => {
    const { source } = setup();
    expect((await source.findExact(fileName))?.playable.sourceKey).toBe("jellyfin:srv");
    expect(await source.findExact("hyperdimension_neptunia_-_s01e01_-_the_goddess_(neptune)_of_planeptune.mkv")).toBeNull();
    expect(await source.findExact("[Group] Hyperdimension Neptunia S01E01 [1080p].mkv")).toBeNull();
  });

  it("reports session progress and marks played, with the older endpoint as fallback", async () => {
    const { source, server } = setup();
    const exact = await source.findExact(fileName);
    if (!exact) throw new Error("not found");
    await source.playable(exact.item);
    const report = { itemId: "ep1", sessionId: "sess1", durationSeconds: 1420, markWatched: false };
    await source.reportPlayback({ ...report, state: "STARTED", positionSeconds: 0 });
    await source.reportPlayback({ ...report, state: "PAUSED", positionSeconds: 61.5 });
    await source.reportPlayback({ ...report, state: "STOPPED", positionSeconds: 1300, markWatched: true });
    const sessions = server.requests.filter((r) => r.url.pathname.startsWith("/Sessions/Playing"));
    expect(sessions.map((r) => r.url.pathname)).toEqual(["/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"]);
    expect(JSON.parse(sessions[1].body)).toMatchObject({
      ItemId: "ep1",
      MediaSourceId: "ms1",
      PlaySessionId: "sess1",
      PositionTicks: 615000000,
      IsPaused: true,
    });
    const played = server.requests.filter((r) => r.url.pathname.includes("PlayedItems"));
    expect(played.map((r) => r.url.pathname)).toEqual(["/UserPlayedItems/ep1", "/Users/u1/PlayedItems/ep1"]);
    expect(played[0].url.searchParams.get("userId")).toBe("u1");
  });

  it("an unreachable server is said to be unreachable", async () => {
    const down = new JellyfinClient(info, { fetch: fakeServer(() => "unreachable").fetch, pageProtocol: "http:" });
    const error = await down.publicInfo(BASE).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(MediaSourceError);
    expect((error as MediaSourceError).message).toBe("Cannot reach jf.test:8096 (fetch failed)");
    expect((error as MediaSourceError).httpCode).toBeNull();
  });
});
