import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MalClient } from "../mal/client";
import type { EpisodeInfo } from "../mal/episode";
import { fakeFetch } from "../mal/fakeFetch";
import { MalService } from "../mal/service";
import { memoryStorage } from "../mal/storage";
import { AniSkipClient } from "./aniskip";
import type { Chapter, SkipKind, SkipSegment } from "./segment";
import { CHAPTERS_SETTLE_MILLIS, PROMPT_MILLIS, SkipService, type SkipDetect, type SkipFile, type SkipSettings } from "./skipService";

// Ported from SkipServiceTest.kt: openings and endings skipped by themselves, offered with a Skip button, or left
// alone. Its listening tests drive the service with a stand-in for the detector (which has tests of its own).

let clock: number;
let seeks: number[];
let notes: string[];
let aniSkipAsks: string[];
let canSeek: boolean;
let roomSkip: { inRoom: boolean; on: boolean };
let settings: SkipSettings;
let services: MalService[];

/** Hero Academy (MyAnimeList 100), episode 3 of a 1440 s file: opening 28.7–118.7, ending 1350–1440. */
const server = () =>
  fakeFetch((r) => {
    const path = r.url.pathname;
    if (path === "/yarmiplay/mal/api/anime/100" && r.headers["x-mal-client-id"] === "client-id") {
      return { body: { id: 100, title: "Hero Academy", num_episodes: 12, media_type: "tv", related_anime: [] } };
    }
    if (path === "/skip/skip-times/100/3") {
      aniSkipAsks.push(r.url.searchParams.get("episodeLength")!);
      return {
        body: {
          found: true,
          results: [
            { interval: { startTime: 28.7, endTime: 118.7 }, skipType: "op", episodeLength: 1440 },
            { interval: { startTime: 1350, endTime: 1440 }, skipType: "ed", episodeLength: 1440 },
          ],
        },
      };
    }
    return { status: 404 };
  });

beforeEach(() => {
  clock = 1_000_000;
  seeks = [];
  notes = [];
  aniSkipAsks = [];
  canSeek = true;
  roomSkip = { inRoom: false, on: false };
  settings = { autoSkipAlone: false, showSkipButton: true, detectSkips: true };
  services = [];
});

afterEach(() => services.forEach((s) => s.dispose()));

let position = 0;

function service(o: { markers?: (file: SkipFile) => Promise<SkipSegment[]>; detect?: SkipDetect } = {}): SkipService {
  const http = server();
  // Signed out: MyAnimeList is asked with the Client ID alone.
  const mal = new MalService({
    client: new MalClient("client-id", { passthroughBase: () => "https://home.example:8999", fetch: http.fetch, now: () => clock }),
    storage: memoryStorage(),
    now: () => clock,
    log: () => {},
  });
  services.push(mal);
  position = 0;
  return new SkipService({
    seek: (p) => {
      seeks.push(p);
      position = p;
    },
    canSeek: () => canSeek,
    roomSkip: () => roomSkip,
    settings: () => settings,
    notify: (text) => notes.push(text),
    now: () => clock,
    identify: (info) => mal.identify(info),
    aniSkip: new AniSkipClient({ fetch: http.fetch, apiUrl: "https://aniskip.example/skip" }),
    markers: o.markers,
    detect: o.detect,
    log: () => {},
  });
}

const hero: EpisodeInfo = { title: "Hero Academy", season: 1, episode: 3, seriesKey: "file/hero academy", malId: 100 };

async function eventually(what: string, condition: () => boolean) {
  const until = Date.now() + 2_000;
  while (Date.now() < until) {
    if (condition()) return;
    await new Promise((r) => setTimeout(r, 2));
  }
  throw new Error(`Timed out waiting for ${what}`);
}

const settle = () => new Promise((r) => setTimeout(r, 20));

async function play(chapters: Chapter[] = []): Promise<SkipService> {
  const s = service();
  s.fileChanged({ key: "file:///anime/Hero Academy - 03.mkv", episode: hero });
  s.loaded(1440, chapters);
  await eventually("the opening and ending", () => s.segments.value.length === 2);
  return s;
}

/** Playback at [p], playing. */
function at(s: SkipService, p: number) {
  position = p;
  s.tick(p, false);
}

const chapter = (title: string, start: number, end: number): Chapter => ({ title, start, end });

describe("SkipService", () => {
  it("the opening and ending come from AniSkip with the file's length, without a MyAnimeList account", async () => {
    const s = await play();
    expect(s.segments.value[0].kind).toBe("opening");
    expect(aniSkipAsks).toEqual(["1440.000"]);
  });

  it("the file's own chapters win over AniSkip", async () => {
    const s = await play([chapter("Prologue", 0, 60), chapter("Opening", 60, 150), chapter("Part A", 150, 1340), chapter("ED", 1340, 1425), chapter("Preview", 1425, 1440)]);
    await eventually("the chapters' opening", () => s.segments.value[0]?.start === 60);
    await settle();
    expect(s.segments.value).toEqual([
      { kind: "opening", start: 60, end: 150 },
      { kind: "ending", start: 1340, end: 1425 },
    ]);
  });

  it("a media server's markers count for a show AniSkip doesn't know", async () => {
    const markers = async (file: SkipFile): Promise<SkipSegment[]> =>
      file.key === "http://jellyfin/ep7.mkv"
        ? [
            { kind: "opening", start: 5, end: 95 },
            { kind: "ending", start: 2500, end: 2590 },
          ]
        : [];
    const s = service({ markers });
    // Not an episode MyAnimeList can name: only the server knows.
    s.fileChanged({ key: "http://jellyfin/ep7.mkv", episode: null });
    s.loaded(2600);
    await eventually("the server's markers", () => s.segments.value.length === 2);
    at(s, 6);
    expect(s.prompt.value?.label).toBe("Skip opening");
    expect(aniSkipAsks).toEqual([]); // AniSkip wasn't asked
  });

  it("a Skip button shows for 3 seconds as the opening starts, and skips to its end", async () => {
    const s = await play();
    at(s, 10);
    expect(s.prompt.value).toBeNull();
    at(s, 29.5);
    expect(s.prompt.value?.label).toBe("Skip opening");
    s.skipNow();
    expect(seeks).toEqual([118.7]);
    expect(s.prompt.value).toBeNull();

    // Back into the opening: it plays, no second button.
    at(s, 40);
    at(s, 41);
    expect(s.prompt.value).toBeNull();
  });

  it("the button goes after 3 seconds", async () => {
    const s = await play();
    at(s, 29);
    expect(s.prompt.value).not.toBeNull();
    clock += PROMPT_MILLIS - 1;
    s.tick(30, false);
    expect(s.prompt.value).not.toBeNull();
    clock += 301;
    s.tick(32, true); // paused, it still goes
    expect(s.prompt.value).toBeNull();
    at(s, 32.2);
    expect(s.prompt.value).toBeNull();
    expect(seeks).toEqual([]);
  });

  it("with the button turned off nothing shows", async () => {
    settings = { ...settings, showSkipButton: false };
    const s = await play();
    at(s, 29);
    expect(s.prompt.value).toBeNull();
    expect(seeks).toEqual([]);
  });

  it("alone with auto-skip on, the opening is skipped by itself once", async () => {
    settings = { ...settings, autoSkipAlone: true };
    const s = await play();
    at(s, 29);
    expect(seeks).toEqual([118.7]);
    expect(notes).toEqual(["Skipped the opening"]);
    expect(s.prompt.value).toBeNull();
    clock += 300;
    at(s, 30);
    expect(seeks).toEqual([118.7]); // seeking back into it plays it
  });

  it("a skip the room swallowed is tried again, a few times at most", async () => {
    settings = { ...settings, autoSkipAlone: true };
    const s = await play();
    for (let i = 0; i < 6; i++) {
      at(s, 29 + i * 0.1);
      clock += 1_600;
    }
    expect(seeks).toEqual([118.7, 118.7, 118.7, 118.7]);
    expect(notes).toEqual(["Skipped the opening"]);
  });

  it("in a room the room's setting decides, and an ending that runs to the end stops just before it", async () => {
    settings = { ...settings, autoSkipAlone: false };
    roomSkip = { inRoom: true, on: true };
    const s = await play();
    at(s, 1351);
    expect(seeks).toEqual([1439.5]);
  });

  it("a viewer who follows a managed room's operators neither skips nor gets the button", async () => {
    roomSkip = { inRoom: true, on: true };
    canSeek = false;
    const s = await play();
    at(s, 29);
    expect(seeks).toEqual([]);
    expect(s.prompt.value).toBeNull();
  });

  it("paused or seeking, nothing happens until playback goes on", async () => {
    const s = await play();
    s.tick(29, true);
    s.tick(29, false, true);
    expect(s.prompt.value).toBeNull();
    at(s, 29.2);
    expect(s.prompt.value?.label).toBe("Skip opening");
  });

  // --- Listening -----------------------------------------------------------------------------------------------

  /** A detector stand-in for a season whose parts no other source knows: opening 20–110, ending 1300–1390. */
  function listener(o: { hang?: boolean } = {}) {
    const calls: { kinds: SkipKind[]; signal: AbortSignal }[] = [];
    const state = { hang: o.hang ?? false, sawCancel: false };
    const detect: SkipDetect = (kinds, { signal }) => {
      calls.push({ kinds, signal });
      if (state.hang) {
        return new Promise((_, reject) =>
          signal.addEventListener("abort", () => {
            state.sawCancel = true;
            reject(new Error("aborted"));
          }),
        );
      }
      return Promise.resolve([
        { kind: "opening", start: 20, end: 110 },
        { kind: "ending", start: 1300, end: 1390 },
      ]);
    };
    return { detect, calls, state };
  }

  const season2: EpisodeInfo = { title: "Unknown Season", season: 1, episode: 2, seriesKey: "file/unknown season" };

  async function playEpisode(detect: SkipDetect, chapters: Chapter[] = []) {
    const s = service({ detect });
    s.fileChanged({ key: "file:///anime/Unknown Season - 02.mkv", episode: season2 });
    s.loaded(1440, chapters);
    await settle(); // MyAnimeList and AniSkip know nothing of it
    return s;
  }

  /** Time passing on the player, a tick at a time. */
  function wait(s: SkipService, millis: number) {
    for (let t = 0; t < millis; t += 250) {
      clock += 250;
      s.tick(0, false);
    }
  }

  it("listening finds the opening and ending nothing else knows, once the chapters have had a moment", async () => {
    const l = listener();
    const s = await playEpisode(l.detect);
    wait(s, CHAPTERS_SETTLE_MILLIS - 250);
    expect(l.calls).toEqual([]);
    wait(s, 250);
    await eventually("the opening and ending", () => s.segments.value.length === 2);
    const [opening, ending] = s.segments.value;
    expect([opening.start, opening.end, ending.end]).toEqual([20, 110, 1390]);
    expect(l.calls[0].kinds).toEqual(["opening", "ending"]);
    at(s, 21.5);
    expect(s.prompt.value?.label).toBe("Skip opening");
  });

  it("only what the chapters leave out is listened for", async () => {
    const l = listener();
    const s = await playEpisode(l.detect, [chapter("Cold open", 0, 20), chapter("Opening", 20, 110), chapter("Part A", 110, 1440)]);
    wait(s, CHAPTERS_SETTLE_MILLIS);
    await eventually("the ending", () => s.segments.value.length === 2);
    expect(s.segments.value[0]).toEqual({ kind: "opening", start: 20, end: 110 });
    expect(l.calls.map((c) => c.kinds)).toEqual([["ending"]]);
  });

  it("with listening turned off nothing is listened for", async () => {
    settings = { ...settings, detectSkips: false };
    const l = listener();
    const s = await playEpisode(l.detect);
    wait(s, 3_500);
    await settle();
    expect(l.calls).toEqual([]);
    expect(s.segments.value).toEqual([]);
  });

  it("turning listening off stops it, and on starts it again", async () => {
    const l = listener({ hang: true });
    const s = await playEpisode(l.detect);
    wait(s, CHAPTERS_SETTLE_MILLIS);
    expect(l.calls).toHaveLength(1);
    settings = { ...settings, detectSkips: false };
    s.tick(0, true);
    expect(l.state.sawCancel).toBe(true);
    l.state.hang = false;
    settings = { ...settings, detectSkips: true };
    s.settingsChanged();
    wait(s, CHAPTERS_SETTLE_MILLIS);
    await eventually("the opening and ending", () => s.segments.value.length === 2);
    expect(l.calls).toHaveLength(2);
  });

  it("a viewer who follows a managed room's operators doesn't listen", async () => {
    canSeek = false;
    const l = listener();
    const s = await playEpisode(l.detect);
    wait(s, 3_500);
    expect(l.calls).toEqual([]);
  });

  it("a file that changes while listening drops what was heard", async () => {
    const l = listener({ hang: true });
    const s = await playEpisode(l.detect);
    wait(s, CHAPTERS_SETTLE_MILLIS);
    expect(l.calls).toHaveLength(1);
    s.fileChanged({ key: "file:///other.mkv", episode: null });
    s.loaded(100);
    expect(l.state.sawCancel).toBe(true);
    await settle();
    expect(s.segments.value).toEqual([]);
  });

  it("without an episode number nothing is listened for", async () => {
    const l = listener();
    const s = service({ detect: l.detect });
    s.fileChanged({ key: "file:///movie.mkv", episode: { ...season2, episode: null } });
    s.loaded(5400);
    await settle();
    wait(s, 3_500);
    expect(l.calls).toEqual([]);
  });
});
