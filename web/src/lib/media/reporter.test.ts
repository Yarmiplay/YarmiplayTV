import { describe, expect, it } from "vitest";
import { FakeMediaSource, JF, JF2, PX } from "./fakeMediaSource.testutil";
import type { ServerCopy } from "./locator";
import type { MediaSource, ReportState } from "./mediaSource";
import { PlaybackReporter, type ReportedMedia, type ReportedPlayer } from "./reporter";

// Ported from PlaybackReporterTest.kt. The reporter only gets the player's state, never the player itself, so it
// can't move playback (the Kotlin test's WatchOnlyPlayer); the heartbeat is a call here instead of a coroutine.
const both: ServerCopy[] = [
  { sourceKey: JF, itemId: "j1" },
  { sourceKey: PX, itemId: "p1" },
];

function setup(servers?: MediaSource[]) {
  const jellyfin = new FakeMediaSource(JF);
  const plex = new FakeMediaSource(PX);
  const list = servers ?? [jellyfin, plex];
  let ids = 0;
  const reporter = new PlaybackReporter(() => list, { newSessionId: () => `session-${++ids}`, onError: () => {} });
  let np: ReportedMedia | null = null;
  let state: ReportedPlayer = { url: null, fileLoaded: false, paused: true, seeking: false, position: 0, duration: 0, eofReached: false };
  const push = () => reporter.update(np, state);
  return {
    reporter,
    jellyfin,
    plex,
    play(url = "http://jellyfin/a.mkv", copies: ServerCopy[] = both, paused = false) {
      np = { url, copies, durationHint: 100 };
      state = { url, fileLoaded: true, duration: 100, paused, seeking: false, position: 0, eofReached: false };
      push();
    },
    update(change: Partial<ReportedPlayer>) {
      state = { ...state, ...change };
      push();
    },
    setNowPlaying(next: ReportedMedia | null) {
      np = next;
      push();
    },
    get nowPlaying() {
      return np;
    },
  };
}

const states = (s: FakeMediaSource): ReportState[] => s.reports.map((r) => r.state);

describe("PlaybackReporter", () => {
  it("starts, pauses and stops on every server with the file", async () => {
    const t = setup();
    t.play();
    t.update({ position: 30, paused: true });
    t.update({ paused: false });
    t.setNowPlaying(null);
    await t.reporter.settled();

    const expected: ReportState[] = ["STARTED", "PAUSED", "PLAYING", "STOPPED"];
    expect(states(t.jellyfin)).toEqual(expected);
    expect(states(t.plex)).toEqual(expected);
    expect([...new Set(t.jellyfin.reports.map((r) => r.itemId))]).toEqual(["j1"]);
    expect([...new Set(t.plex.reports.map((r) => r.itemId))]).toEqual(["p1"]);
    expect(new Set([...t.jellyfin.reports, ...t.plex.reports].map((r) => r.sessionId)).size).toBe(1);
    expect(t.jellyfin.reports[1].positionSeconds).toBe(30);
  });

  it("two servers of one kind each get their own reports", () => {
    const jellyfin = new FakeMediaSource(JF);
    const office = new FakeMediaSource(JF2);
    const t = setup([jellyfin, office]);
    t.play(undefined, [
      { sourceKey: JF, itemId: "j1" },
      { sourceKey: JF2, itemId: "j2" },
    ]);
    t.update({ position: 30, paused: true });

    expect(states(jellyfin)).toEqual(["STARTED", "PAUSED"]);
    expect(states(office)).toEqual(["STARTED", "PAUSED"]);
    expect([...new Set(jellyfin.reports.map((r) => r.itemId))]).toEqual(["j1"]);
    expect([...new Set(office.reports.map((r) => r.itemId))]).toEqual(["j2"]);
  });

  it("the heartbeat only reports while playing", () => {
    const t = setup();
    t.play();
    t.reporter.heartbeat();
    expect(states(t.jellyfin)).toEqual(["STARTED", "PLAYING"]);
    t.update({ paused: true });
    t.reporter.heartbeat();
    t.reporter.heartbeat();
    expect(states(t.jellyfin)).toEqual(["STARTED", "PLAYING", "PAUSED"]);
  });

  it("reports a seek once it settles", () => {
    const t = setup();
    t.play();
    t.update({ seeking: true, position: 70 });
    expect(states(t.jellyfin)).toEqual(["STARTED"]);
    t.update({ seeking: false, position: 70 });
    expect(t.jellyfin.reports.at(-1)?.state).toBe("PLAYING");
    expect(t.jellyfin.reports.at(-1)?.positionSeconds).toBe(70);
  });

  it("marks watched once, at ninety percent", () => {
    const t = setup();
    t.play();
    t.update({ position: 89 });
    expect(t.jellyfin.reports.some((r) => r.markWatched)).toBe(false);
    t.update({ position: 91 });
    t.update({ position: 95, paused: true });
    expect(t.jellyfin.reports.filter((r) => r.markWatched)).toHaveLength(1);
    expect(t.plex.reports.filter((r) => r.markWatched)).toHaveLength(1);
  });

  it("the end of the file stops at the end", () => {
    const t = setup();
    t.play();
    t.update({ position: 99.5, eofReached: true, fileLoaded: false });
    const stop = t.jellyfin.reports.at(-1);
    expect(stop?.state).toBe("STOPPED");
    expect(stop?.positionSeconds).toBe(100);
  });

  it("the next file starts a new session", () => {
    const t = setup();
    t.play();
    t.play("http://jellyfin/b.mkv");
    expect(states(t.jellyfin)).toEqual(["STARTED", "STOPPED", "STARTED"]);
    expect(t.jellyfin.reports[0].sessionId).not.toBe(t.jellyfin.reports[2].sessionId);
  });

  it("copies added later get started", () => {
    const t = setup();
    t.play(undefined, both.slice(0, 1));
    expect(t.plex.reports).toEqual([]);
    const np = t.nowPlaying;
    if (!np) throw new Error("nothing playing");
    t.setNowPlaying({ ...np, copies: both });
    expect(states(t.plex)).toEqual(["STARTED"]);
    expect(states(t.jellyfin)).toEqual(["STARTED"]);
  });

  it("skips servers that aren't connected and survives failures", async () => {
    const failing = new FakeMediaSource(JF, { failReports: true });
    const t = setup([failing]);
    t.play();
    t.update({ paused: true });
    await t.reporter.settled();
    expect(t.plex.reports).toEqual([]);
  });

  it("local files and disabled reporting send nothing", () => {
    const t = setup();
    t.play(undefined, []);
    expect(t.jellyfin.reports).toEqual([]);
    t.reporter.enabled = false;
    t.play("http://jellyfin/b.mkv");
    t.update({ paused: true });
    expect(t.jellyfin.reports).toEqual([]);
    expect(t.plex.reports).toEqual([]);
  });

  it("a timer can drive the heartbeat", async () => {
    const t = setup();
    t.play();
    const stop = t.reporter.startHeartbeat(5);
    await new Promise((r) => setTimeout(r, 30));
    stop();
    expect(states(t.jellyfin).filter((s) => s === "PLAYING").length).toBeGreaterThan(0);
  });
});
