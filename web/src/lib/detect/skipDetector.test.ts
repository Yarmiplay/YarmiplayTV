import { describe, expect, it } from "vitest";
import { MemoryCacheStore } from "./cacheStore";
import { FakeSeason } from "./fakeSeason.testing";
import { FingerprintCache, type SkipKind } from "./fingerprintCache";
import { SkipDetector, type SkipSegment } from "./skipDetector";

// Ported from SkipDetectorTest.kt: listening for openings and endings across a season's episodes.

const both: SkipKind[] = ["OPENING", "ENDING"];
const SLOW = 120_000;

/** The app's network rule: a server's episodes on an unmetered network, or on any when the setting says so. */
const settings = { detectOnAnyNetwork: false };

function detector(season: FakeSeason, store: MemoryCacheStore, unmetered = true) {
  return new SkipDetector({
    decoder: season.decoder,
    cache: new FingerprintCache(store),
    siblings: season.siblings,
    mayListenRemote: () => settings.detectOnAnyNetwork || unmetered,
  });
}

function expectPart(expected: SkipSegment, actual: SkipSegment | undefined) {
  expect(actual?.kind).toBe(expected.kind);
  expect(Math.abs(actual!.start - expected.start), `start of ${JSON.stringify(actual)}`).toBeLessThanOrEqual(1.0);
  expect(Math.abs(actual!.end - expected.end), `end of ${JSON.stringify(actual)}`).toBeLessThanOrEqual(0.6);
}

describe("SkipDetector", () => {
  it("the song the episodes before and after share is the opening, and the one at the end the ending", { timeout: SLOW }, async () => {
    const season = new FakeSeason();
    const found = await detector(season, new MemoryCacheStore()).detect(season.nowPlaying(2), season.duration, both);
    expectPart({ kind: "OPENING", start: 20.0, end: 110.0 }, found.find((p) => p.kind === "OPENING"));
    expectPart({ kind: "ENDING", start: 1300.0, end: 1390.0 }, found.find((p) => p.kind === "ENDING"));
    // Episode 2, then 3 and 1, the start and the end of each.
    expect([...new Set(season.reads.map(([n]) => n))]).toEqual([2, 3, 1]);
    expect(season.reads.length).toBe(6);
    // Every file opened was closed.
    expect(season.opened.every((s) => s.closed)).toBe(true);
  });

  it("the next episode needs only its own and its next one read, and again none", { timeout: SLOW }, async () => {
    const season = new FakeSeason();
    const store = new MemoryCacheStore();
    const first = detector(season, store);
    await first.detect(season.nowPlaying(2), season.duration, both);
    season.reads.length = 0;

    const third = await first.detect(season.nowPlaying(3), season.duration, ["OPENING"]);
    expect(third.length).toBe(1);
    expectPart({ kind: "OPENING", start: 30.0, end: 120.0 }, third[0]);
    // Episode 3 was read already; 2 and 3 were compared; only 4 is new.
    expect(season.reads.map(([n]) => n)).toEqual([4]);

    season.reads.length = 0;
    // Another visit to the page: all from the cache.
    const again = await detector(season, store).detect(season.nowPlaying(2), season.duration, both);
    expect(again.length).toBe(2);
    expect(season.reads).toEqual([]);
  });

  it("a server's episodes aren't read on a metered network unless the setting allows it", { timeout: SLOW }, async () => {
    const season = new FakeSeason({ remote: true });
    try {
      expect(await detector(season, new MemoryCacheStore(), false).detect(season.nowPlaying(2), season.duration, both)).toEqual([]);
      expect(season.reads).toEqual([]);

      settings.detectOnAnyNetwork = true;
      expect((await detector(season, new MemoryCacheStore(), false).detect(season.nowPlaying(2), season.duration, both)).length).toBe(2);
    } finally {
      settings.detectOnAnyNetwork = false;
    }
  });

  it("episodes that share nothing have no opening", { timeout: SLOW }, async () => {
    const season = new FakeSeason({ songSeconds: 0.0 });
    expect(await detector(season, new MemoryCacheStore()).detect(season.nowPlaying(2), season.duration, ["OPENING"])).toEqual([]);
  });

  it("in a season of two the other episode is enough", { timeout: SLOW }, async () => {
    const season = new FakeSeason({ count: 2 });
    const found = await detector(season, new MemoryCacheStore()).detect(season.nowPlaying(1), season.duration, ["OPENING"]);
    expect(found.length).toBe(1);
    expectPart({ kind: "OPENING", start: 10.0, end: 100.0 }, found[0]);
  });

  it("each episode's opening is found wherever it has it", { timeout: SLOW }, async () => {
    // Episode 3 has a longer cold open than the others.
    const season = new FakeSeason({ openingAt: (n) => (n === 3 ? 60.0 : 20.0) });
    const found = await detector(season, new MemoryCacheStore()).detect(season.nowPlaying(2), season.duration, ["OPENING"]);
    expect(found.length).toBe(1);
    expectPart({ kind: "OPENING", start: 20.0, end: 110.0 }, found[0]);
  });

  it("cancelling stops reading", { timeout: SLOW }, async () => {
    const season = new FakeSeason();
    season.hang = true;
    const controller = new AbortController();
    const job = detector(season, new MemoryCacheStore()).detect(season.nowPlaying(2), season.duration, both, controller.signal);
    const outcome = job.then(
      () => "done",
      (e: unknown) => (e instanceof DOMException ? e.name : String(e)),
    );
    while (season.reads.length === 0) await new Promise((resolve) => setTimeout(resolve, 10));
    controller.abort();
    expect(await outcome).toBe("AbortError");
    expect(season.sawCancel).toBe(true);
    expect(season.opened.every((s) => s.closed)).toBe(true);
  });
});
