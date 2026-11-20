import { afterEach, describe, expect, it } from "vitest";
import { isGrowing } from "../player/bytes";
import { FAR_BYTES, GrowingSource, SegmentIndex } from "./growing";

const MIB = 1 << 20;
const NOMINAL = 10 * 1024 * MIB;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** The relay as a growing file's reader sees it: written bytes, a segment index, and every request it got. */
class FakeRelay {
  readonly bytes = new Uint8Array(8 * MIB).map((_, i) => i % 251);
  length = 0;
  growing = true;
  live = true;
  segments: [number, number, number][] = [];
  gone = false;
  readonly ranges: [number, number][] = [];
  readonly segmentRequests: number[] = [];

  write(to: number) {
    this.length = to;
  }

  /** Cuts a segment every [every] bytes up to the written length, each lasting [seconds]. */
  index(every: number, seconds = 2) {
    const covered = this.segments.reduce((n, s) => n + s[1], 0);
    for (let at = covered; at + every <= this.length; at += every) this.segments.push([at, every, seconds]);
  }

  readonly fetch: typeof fetch = async (input, init) => {
    const url = new URL(String(input));
    const headers = (init?.headers ?? {}) as Record<string, string>;
    expect(headers.Authorization).toBe("Bearer t");
    if (url.pathname.endsWith("/segments")) {
      const from = Number(url.searchParams.get("from"));
      this.segmentRequests.push(from);
      if (this.gone) return new Response("No such file", { status: 404 });
      const body = { growing: this.growing, live: this.live, length: this.length, from, segments: this.segments.slice(from) };
      return new Response(JSON.stringify(body), { status: 200 });
    }
    const m = /^bytes=(\d+)-(\d+)$/.exec(headers.Range ?? "")!;
    const [a, b] = [Number(m[1]), Number(m[2])];
    this.ranges.push([a, b]);
    if (a >= this.length) return new Response(null, { status: 416 });
    return new Response(this.bytes.slice(a, Math.min(b + 1, this.length)), { status: 206 });
  };
}

const opened: GrowingSource[] = [];
afterEach(() => opened.splice(0).forEach((s) => s.close()));

async function open(relay: FakeRelay, opts: { live?: boolean; duration?: number } = {}) {
  const source = await GrowingSource.open({
    url: "http://relay.example/yarmiplay/files/g1?mode=stream",
    segmentsUrl: "http://relay.example/yarmiplay/files/g1/segments",
    headers: { Authorization: "Bearer t" },
    name: "Part 1",
    size: NOMINAL,
    media: { growing: relay.growing, live: opts.live ?? relay.live, contentType: "video/mp2t", length: relay.length },
    duration: opts.duration ?? 0,
    fetch: relay.fetch,
    pollMs: 5,
  });
  opened.push(source);
  return source;
}

describe("SegmentIndex", () => {
  it("adds pages in order, skipping what it has and ignoring gaps", () => {
    const index = new SegmentIndex();
    expect(index.append(0, [{ offset: 0, length: 100, duration: 2 }, { offset: 100, length: 80, duration: 2.5 }])).toBe(true);
    expect(index.append(1, [{ offset: 100, length: 80, duration: 2.5 }, { offset: 180, length: 90, duration: 2 }])).toBe(true);
    expect(index.append(5, [{ offset: 900, length: 10, duration: 2 }])).toBe(false);
    expect(index.append(3, [])).toBe(false);
    expect(index.count).toBe(3);
    expect(index.seconds).toBeCloseTo(6.5);
  });

  it("maps a time to the start of the segment holding it, clamping at both ends", () => {
    const index = new SegmentIndex();
    expect(index.seekPoint(30)).toEqual({ offset: 0, time: 0 });
    index.append(0, [
      { offset: 0, length: 100, duration: 2 },
      { offset: 100, length: 80, duration: 2 },
      { offset: 180, length: 90, duration: 2 },
    ]);
    expect(index.seekPoint(-3)).toEqual({ offset: 0, time: 0 });
    expect(index.seekPoint(0)).toEqual({ offset: 0, time: 0 });
    expect(index.seekPoint(1.99)).toEqual({ offset: 0, time: 0 });
    expect(index.seekPoint(2)).toEqual({ offset: 100, time: 2 });
    expect(index.seekPoint(3.5)).toEqual({ offset: 100, time: 2 });
    expect(index.seekPoint(5.9)).toEqual({ offset: 180, time: 4 });
    expect(index.seekPoint(500)).toEqual({ offset: 180, time: 4 });
  });

  it("keeps times inside the index and moves later ones to the newest segment", () => {
    const index = new SegmentIndex();
    expect(index.clamp(12)).toBe(0);
    index.append(0, [
      { offset: 0, length: 100, duration: 2 },
      { offset: 100, length: 80, duration: 2 },
    ]);
    expect(index.clamp(-1)).toBe(0);
    expect(index.clamp(3.25)).toBe(3.25);
    expect(index.clamp(4)).toBe(2);
    expect(index.clamp(3600)).toBe(2);
  });
});

describe("GrowingSource", () => {
  it("reads what is written and never asks for bytes past the written length", async () => {
    const relay = new FakeRelay();
    relay.write(2 * MIB + 300_000);
    const source = await open(relay);
    expect(isGrowing(source)).toBe(true);
    expect(source.size).toBe(NOMINAL);

    const head = await source.read(10, 1000);
    expect(Array.from(head.subarray(0, 4))).toEqual([10, 11, 12, 13]);
    expect(head.length).toBe(1000);

    // Asks for more than is written: gets up to the written end.
    const tail = await source.read(2 * MIB + 200_000, 512 * 1024);
    expect(tail.length).toBe(100_000);
    expect(tail[0]).toBe((2 * MIB + 200_000) % 251);
    for (const [, end] of relay.ranges) expect(end).toBeLessThan(relay.length);
  });

  it("waits at the written end until the server writes more", async () => {
    const relay = new FakeRelay();
    relay.write(MIB);
    const source = await open(relay);
    let done = false;
    const pending = source.read(MIB, 4096).then((d) => {
      done = true;
      return d;
    });
    await sleep(40);
    expect(done).toBe(false);
    relay.write(MIB + 2048);
    const data = await pending;
    expect(data.length).toBe(2048);
    expect(data[0]).toBe(MIB % 251);
  });

  it("gives the end of the file at the written end once the server stops writing", async () => {
    const relay = new FakeRelay();
    relay.write(MIB);
    const source = await open(relay);
    const pending = source.read(MIB, 4096);
    await sleep(20);
    relay.growing = false;
    expect((await pending).length).toBe(0);
    expect(source.ended).toBe(true);
    expect((await source.read(MIB + 10, 10)).length).toBe(0);
  });

  it("answers a probe far past the written end at once, without asking the server for bytes", async () => {
    const relay = new FakeRelay();
    relay.write(MIB);
    const source = await open(relay);
    const before = relay.ranges.length;
    const started = Date.now();
    expect((await source.read(NOMINAL - 250_000, 250_000)).length).toBe(0);
    expect((await source.read(MIB + FAR_BYTES, 10)).length).toBe(0);
    expect(Date.now() - started).toBeLessThan(500);
    expect(relay.ranges.length).toBe(before);
  });

  it("keeps the index current while it grows, asking only for segments it doesn't have", async () => {
    const relay = new FakeRelay();
    relay.write(3 * MIB);
    relay.index(MIB);
    const source = await open(relay);
    expect(source.indexedSeconds).toBe(6);
    expect(source.playableDuration()).toBe(6);
    let changes = 0;
    source.onChange(() => changes++);
    relay.write(5 * MIB);
    relay.index(MIB);
    await sleep(60);
    expect(source.indexedSeconds).toBe(10);
    expect(source.playableDuration()).toBe(10);
    expect(changes).toBeGreaterThan(0);
    expect(relay.segmentRequests[0]).toBe(0);
    expect(relay.segmentRequests).toContain(3);
    expect(source.seekPoint(7)).toEqual({ offset: 3 * MIB, time: 6 });
    expect(source.clampSeconds(60)).toBe(8);
  });

  it("shows the whole length for a file that will end, and what was indexed once it has", async () => {
    const relay = new FakeRelay();
    relay.live = false;
    relay.write(2 * MIB);
    relay.index(MIB);
    const source = await open(relay, { duration: 1500 });
    expect(source.playableDuration()).toBe(1500);
    // Seeking stays inside what exists, even though the length shown is longer.
    expect(source.clampSeconds(900)).toBe(2);
    relay.segments.push([2 * MIB, 300_000, 1.25]);
    relay.write(2 * MIB + 300_000);
    relay.growing = false;
    await source.refresh();
    expect(source.ended).toBe(true);
    expect(source.playableDuration()).toBeCloseTo(5.25);
  });

  it("treats a file the server no longer has as ended", async () => {
    const relay = new FakeRelay();
    relay.write(MIB);
    const source = await open(relay);
    relay.gone = true;
    await source.refresh();
    expect(source.ended).toBe(true);
  });

  it("lets a read waiting for bytes go when aborted", async () => {
    const relay = new FakeRelay();
    relay.write(MIB);
    const source = await open(relay);
    const pending = source.read(MIB, 10);
    await sleep(15);
    source.abort();
    await expect(pending).rejects.toThrow();
    await expect(source.read(0, 10)).rejects.toThrow();
  });
});
