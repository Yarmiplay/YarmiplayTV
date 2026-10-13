import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MalClient, MalError } from "./client";
import type { EpisodeInfo } from "./episode";
import { fakeFetch, type FakeAnswer, type SeenRequest } from "./fakeFetch";
import { emptyListStatus, listSummary, malAnime, statusValue, type MalListStatus } from "./models";
import { MalService, malRedirectUri, type Schedule } from "./service";
import { MalKeys, MalStore, memoryStorage } from "./storage";
import { MalTracker } from "./tracker";

// Ported from MalServiceTest.kt: the service against a fake YarmiplayServerTV passthrough to MyAnimeList, with
// what it keeps in memory storage.

const BASE = "https://home.example:8999";
const API = "/yarmiplay/mal/api";

let writes: string[];
let detailFetches: number[];
let failWrites: boolean;
/** Hero Academy: 12 episodes, and what's on the list for it. */
let listStatus: Record<string, unknown> | null;
let clock: number;
let server: ReturnType<typeof fakeFetch>;
let storage: ReturnType<typeof memoryStorage>;
let timers: { at: number; run: () => void; cancelled: boolean }[];
let services: MalService[];

function answer(r: SeenRequest): FakeAnswer {
  const path = r.url.pathname;
  if (r.method === "PATCH") {
    if (failWrites) return { status: 503 };
    writes.push(r.body);
    const watched = Number(/num_watched_episodes=(\d+)/.exec(r.body)?.[1] ?? "0");
    const status = /(?:^|&)status=(\w+)/.exec(r.body)?.[1] ?? "watching";
    listStatus = { status, score: 0, num_episodes_watched: watched, is_rewatching: false };
    return { body: listStatus };
  }
  if (r.method === "DELETE") {
    writes.push("DELETE");
    listStatus = null;
    return { status: 200, body: "" };
  }
  if (path === `${API}/anime`) {
    const withList = listStatus && (r.url.searchParams.get("fields") ?? "").includes("my_list_status");
    return { body: { data: [{ node: { id: 100, title: "Hero Academy", num_episodes: 12, media_type: "tv", ...(withList ? { my_list_status: listStatus } : {}) } }] } };
  }
  if (path === `${API}/anime/100`) {
    detailFetches.push(100);
    return { body: { id: 100, title: "Hero Academy", num_episodes: 12, media_type: "tv", related_anime: [], ...(listStatus ? { my_list_status: listStatus } : {}) } };
  }
  if (path === `${API}/users/@me/animelist`) return { body: { data: [] } };
  if (path === "/yarmiplay/mal/token") return { body: { access_token: "access-new", refresh_token: "refresh-new", expires_in: 3600 } };
  if (path === `${API}/users/@me`) return { body: { id: 7, name: "ana", picture: null } };
  return { status: 404 };
}

/** Timers the test runs by hand. */
const schedule: Schedule = (millis, run) => {
  const timer = { at: clock + millis, run, cancelled: false };
  timers.push(timer);
  return () => void (timer.cancelled = true);
};

function signedIn() {
  new MalStore(storage).saveAccount({ tokens: { accessToken: "access", refreshToken: "refresh", expiresAtMillis: Number.MAX_SAFE_INTEGER }, userName: "ana", picture: null });
}

beforeEach(() => {
  writes = [];
  detailFetches = [];
  failWrites = false;
  listStatus = null;
  clock = 1_000_000;
  server = fakeFetch(answer);
  storage = memoryStorage();
  timers = [];
  services = [];
  signedIn();
});

afterEach(() => services.forEach((s) => s.dispose()));

function service() {
  const s = new MalService({
    client: new MalClient("client-id", { passthroughBase: () => BASE, fetch: server.fetch, now: () => clock }),
    storage,
    now: () => clock,
    today: () => "2026-10-12",
    schedule,
    log: () => {},
  });
  services.push(s);
  return s;
}

const ep = (n: number, name = "Hero Academy"): EpisodeInfo => ({ title: name, season: 1, episode: n, seriesKey: "jellyfin:x/series-1" });

async function eventually(what: string, condition: () => boolean) {
  const until = Date.now() + 2_000;
  while (Date.now() < until) {
    if (condition()) return;
    await new Promise((r) => setTimeout(r, 5));
  }
  throw new Error(`Timed out waiting for ${what}`);
}

const status = (fields: Partial<MalListStatus>): MalListStatus => ({ ...emptyListStatus(), ...fields });

describe("MalService", () => {
  it("progress only goes up, the first episode starts, the last completes, and a completed show is a rewatch", () => {
    const none = MalService.progressUpdate(null, 1, 12, "2026-10-12")!;
    expect(none.status).toBe("watching");
    expect(none.watchedEpisodes).toBe(1);
    expect(none.startDate).toBe("2026-10-12");
    expect(none.finishDate ?? null).toBeNull();

    const watching = status({ status: "watching", watchedEpisodes: 5, startDate: "2026-01-01" });
    expect(MalService.progressUpdate(watching, 4, 12, "d")).toBeNull(); // already counted
    expect(MalService.progressUpdate(watching, 6, 12, "d")!.watchedEpisodes).toBe(6);
    expect(MalService.progressUpdate(watching, 6, 12, "d")!.startDate ?? null).toBeNull(); // start date kept

    const last = MalService.progressUpdate(watching, 12, 12, "2026-10-12")!;
    expect(last.status).toBe("completed");
    expect(last.finishDate).toBe("2026-10-12");

    const planned = MalService.progressUpdate(status({ status: "plan_to_watch" }), 3, 0, "d")!;
    expect([planned.status, planned.watchedEpisodes]).toEqual(["watching", 3]);

    const completed = status({ status: "completed", watchedEpisodes: 12, timesRewatched: 1 });
    expect(MalService.progressUpdate(completed, 1, 12, "d")!.isRewatching).toBe(true);
    const done = MalService.progressUpdate({ ...completed, isRewatching: true, watchedEpisodes: 11 }, 12, 12, "d")!;
    expect([done.isRewatching, done.timesRewatched]).toEqual([false, 2]);
  });

  it("a sure match is updated when watched, and Undo takes it back", async () => {
    const mal = service();
    mal.playing(ep(1));
    await eventually("the match", () => mal.state.value.kind === "matched");
    await mal.watched(ep(1));
    expect(writes).toEqual(["status=watching&num_watched_episodes=1&start_date=2026-10-12"]);
    expect(mal.notice.value?.undo).not.toBeNull();
    expect(mal.notice.value!.text).toContain("Hero Academy · ep 1/12");
    expect(mal.canUndo.value).toBe(true);

    await mal.undo();
    expect(writes[1]).toBe("DELETE");
    expect(mal.canUndo.value).toBe(false);
    expect(mal.notice.value!.text).toBe("MAL: undone");
  });

  it("asking first sends nothing until confirmed", async () => {
    new MalStore(storage).savePrefs(true, true);
    const mal = service();
    mal.playing(ep(2));
    await eventually("the match", () => mal.state.value.kind === "matched");
    await mal.watched(ep(2));
    expect(mal.notice.value?.confirm).not.toBeNull();
    await new Promise((r) => setTimeout(r, 50));
    expect(writes).toEqual([]);
    mal.notice.value!.confirm!();
    await eventually("the update", () => writes.length === 1);
  });

  it("an unsure match waits for the user's pick, which is remembered and applied", async () => {
    const mal = service();
    const info = ep(3, "Something Else Entirely");
    mal.playing(info);
    await eventually("unsure", () => mal.state.value.kind === "unsure");
    await mal.watched(info);
    expect(mal.notice.value?.pick).toBe(true);
    expect(writes).toEqual([]); // nothing sent while unsure

    const s = mal.state.value;
    if (s.kind !== "unsure") throw new Error("not unsure");
    const candidate = s.candidates.find((a) => a.id === 100)!;
    await mal.choose(info, candidate, 3);
    expect(writes).toHaveLength(1);
    expect(writes[0]).toContain("num_watched_episodes=3");
    expect(mal.store.value.mappings[MalService.mappingKey(info)]?.malId).toBe(100);
    expect(JSON.parse(storage.getItem(MalKeys.mappings)!)).toEqual({ "jellyfin:x/series-1|1": { malId: 100, offset: 0 } });
  });

  it("updates that can't be sent are queued and sent later", async () => {
    const mal = service();
    mal.playing(ep(1));
    await eventually("the match", () => mal.state.value.kind === "matched");
    failWrites = true;
    await mal.watched(ep(1));
    expect(mal.store.value.queue.map((q) => q.animeId)).toEqual([100]);
    expect(mal.notice.value!.text).toBe("MyAnimeList can't be reached; the update will be sent later");
    // Kept, for after a reload.
    expect(new MalStore(storage).value.queue).toEqual([{ animeId: 100, update: { status: "watching", watchedEpisodes: 1, startDate: "2026-10-12" } }]);
    // Tried again in a minute.
    const retry = timers.filter((t) => !t.cancelled).pop()!;
    expect(retry.at - clock).toBe(60_000);

    failWrites = false;
    await mal.flushQueue();
    expect(writes).toHaveLength(1);
    expect(mal.store.value.queue).toEqual([]);
    expect(storage.getItem(MalKeys.queue)).toBeNull();
  });

  it("a queue left from before is sent once signed in", async () => {
    new MalStore(storage).saveQueue([{ animeId: 100, update: { score: 8 } }]);
    service();
    const retry = timers.find((t) => !t.cancelled)!;
    expect(retry.at).toBe(clock);
    retry.run();
    await eventually("sent", () => writes.length === 1);
    expect(writes[0]).toBe("score=8");
  });

  it("signing out deletes everything kept for MyAnimeList", () => {
    const store = new MalStore(storage);
    store.savePrefs(false, true);
    store.saveMapping("a|1", { malId: 100, offset: 0 });
    store.saveIgnored(["b"], { b: "Bee" });
    store.saveQueue([{ animeId: 100, update: { score: 8 } }]);
    const mal = service();
    mal.signOut();
    for (const key of Object.values(MalKeys)) expect(storage.getItem(key)).toBeNull();
    const s = mal.store.value;
    expect(s.account).toBeNull();
    expect(Object.keys(s.mappings).length + s.ignored.length + s.queue.length).toBe(0);
    expect([s.autoUpdate, s.askFirst]).toEqual([true, false]);
    expect(mal.signedIn).toBe(false);
    expect(mal.state.value).toEqual({ kind: "signedOut" });
    expect(timers.every((t) => t.cancelled)).toBe(true);
  });

  it("MyAnimeList content is kept at most 24 hours", async () => {
    const mal = service();
    await mal.lookup(ep(1));
    const first = detailFetches.length;
    await mal.lookup(ep(1));
    expect(detailFetches.length).toBe(first); // cached
    clock += 24 * 60 * 60 * 1000;
    await mal.lookup(ep(1));
    expect(detailFetches.length).toBeGreaterThan(first); // fetched again after a day
  });

  it("the tracker reports an episode once it's 90 percent watched", async () => {
    const mal = service();
    const tracker = new MalTracker(mal);
    const file = { key: "url-4", episode: ep(4), durationHint: 100 };
    const at = (position: number) => tracker.update(file, { key: "url-4", fileLoaded: true, duration: 100, position, ended: false });
    tracker.update(file, { key: null, fileLoaded: false, duration: 0, position: 0, ended: false });
    await eventually("the match", () => mal.state.value.kind === "matched");
    at(50);
    await new Promise((r) => setTimeout(r, 50));
    expect(writes).toEqual([]);
    at(91);
    await eventually("the update", () => writes.length === 1);
    at(99);
    await new Promise((r) => setTimeout(r, 50));
    expect(writes).toHaveLength(1); // once per file
  });

  it("the search finds shows with your entry, and adds, changes and removes them", async () => {
    const mal = service();
    expect(await mal.findShows("He")).toEqual([]); // MyAnimeList needs 3 letters

    let found = (await mal.findShows("Hero Academy"))[0];
    expect(found.myListStatus).toBeNull(); // not on the list yet
    expect(listSummary(found)).toBe("Not on your list");

    const added = await mal.update(found, { status: "plan_to_watch" });
    expect(statusValue(added)).toBe("plan_to_watch");
    expect(writes[writes.length - 1]).toBe("status=plan_to_watch");
    found = { ...found, myListStatus: added };

    const next = MalService.progressUpdate(found.myListStatus, 1, found.numEpisodes, "2026-10-12")!;
    const one = await mal.update(found, next);
    expect(one?.watchedEpisodes).toBe(1);
    expect(writes[writes.length - 1]).toBe("status=watching&num_watched_episodes=1&start_date=2026-10-12");

    // Searching again shows the entry as MyAnimeList has it.
    found = (await mal.findShows("Hero Academy"))[0];
    expect(found.myListStatus?.watchedEpisodes).toBe(1);

    expect(await mal.remove(found)).toBe(true);
    expect(writes[writes.length - 1]).toBe("DELETE");
    expect((await mal.findShows("Hero Academy"))[0].myListStatus).toBeNull();
  });

  // --- The browser's own -----------------------------------------------------------------------------------------

  it("signing in goes through the callback page, and only with the state it was given", async () => {
    storage = memoryStorage();
    const session = memoryStorage();
    const mal = new MalService({
      client: new MalClient("client-id", { passthroughBase: () => BASE, fetch: server.fetch, now: () => clock }),
      storage,
      session,
      schedule,
    });
    services.push(mal);
    expect(mal.signedIn).toBe(false);
    expect(mal.state.value).toEqual({ kind: "idle" });
    const redirect = malRedirectUri("https://tv.yarmiplay.com", "/watch/");
    expect(redirect).toBe("https://tv.yarmiplay.com/watch/mal-callback.html");
    const url = new URL(mal.beginSignIn(redirect));
    expect(url.searchParams.get("redirect_uri")).toBe(redirect);
    const state = url.searchParams.get("state")!;
    const verifier = url.searchParams.get("code_challenge")!;

    await expect(mal.completeSignIn("the-code", "another-state")).rejects.toBeInstanceOf(MalError);
    expect(server.requests).toEqual([]);

    await mal.completeSignIn("the-code", state);
    const token = server.requests.find((r) => r.url.pathname === "/yarmiplay/mal/token")!;
    const form = new URLSearchParams(token.body);
    expect([form.get("code"), form.get("code_verifier"), form.get("redirect_uri")]).toEqual(["the-code", verifier, redirect]);
    expect(mal.signedIn).toBe(true);
    expect(mal.store.value.account?.userName).toBe("ana");
    expect(JSON.parse(storage.getItem(MalKeys.account)!).tokens.accessToken).toBe("access-new");
    // Used once.
    await expect(mal.completeSignIn("the-code", state)).rejects.toBeInstanceOf(MalError);
  });

  it("without a passthrough, the lookup says why and an update waits in the queue", async () => {
    const mal = new MalService({
      client: new MalClient("client-id", { passthroughBase: () => null, fetch: server.fetch }),
      storage,
      schedule,
      log: () => {},
    });
    services.push(mal);
    mal.playing(ep(1));
    await eventually("the failure", () => mal.state.value.kind === "failed");
    expect(mal.state.value).toMatchObject({ message: "MyAnimeList needs a YarmiplayServerTV 1.8 room or a saved one" });
    await mal.edit(malAnime({ id: 5, title: "Somebody's show" }), { score: 7 });
    expect(mal.store.value.queue).toEqual([{ animeId: 5, update: { score: 7 } }]);
    expect(server.requests).toEqual([]);
  });

  it("an ignored show isn't looked up or updated, and is listed with its name", async () => {
    const mal = service();
    mal.playing(ep(1));
    await eventually("the match", () => mal.state.value.kind === "matched");
    mal.ignore("jellyfin:x/series-1", true, "Hero Academy");
    expect(mal.state.value.kind).toBe("ignored");
    expect(mal.store.value.ignoredNames).toEqual({ "jellyfin:x/series-1": "Hero Academy" });
    expect(await mal.identify(ep(1))).toBeNull();
    await mal.watched(ep(1));
    expect(writes).toEqual([]);
    mal.ignore("jellyfin:x/series-1", false);
    await eventually("matched again", () => mal.state.value.kind === "matched");
    expect(mal.store.value.ignored).toEqual([]);
  });
});

