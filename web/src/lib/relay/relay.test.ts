import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpSource, isGrowing } from "../player/bytes";
import type { RelayFile, YarmiplaySession } from "../syncplay/yarmiplay";
import { GrowingSource } from "./growing";
import { offersFor, openRelayFile } from "./relay";

const session: YarmiplaySession = { token: "tok", protocol: 1, baseUrl: "https://relay.example:8999" };
const plain: RelayFile = { id: "p1", name: "Movie.mkv", size: 5000, duration: 60, quickHash: "q", sources: 1, cachedBytes: 0, rate: 0 };
const growing: RelayFile = {
  id: "g 1",
  name: "Part 1",
  size: 10 * 1024 ** 3,
  duration: 0,
  quickHash: "h",
  sources: 1,
  cachedBytes: 4096,
  rate: 0,
  media: { growing: true, live: true, contentType: "video/mp2t", length: 4096 },
};

afterEach(() => vi.unstubAllGlobals());

describe("openRelayFile", () => {
  it("opens a growing file with its segment index, under the session", async () => {
    const urls: string[] = [];
    vi.stubGlobal("fetch", async (input: string, init?: RequestInit) => {
      urls.push(String(input));
      expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer tok");
      return new Response(JSON.stringify({ growing: true, live: true, length: 4096, from: 0, segments: [[0, 4096, 2]] }), { status: 200 });
    });
    const source = await openRelayFile(session, growing);
    try {
      expect(source).toBeInstanceOf(GrowingSource);
      expect(isGrowing(source)).toBe(true);
      expect(source.size).toBe(growing.size);
      expect(urls[0]).toBe("https://relay.example:8999/yarmiplay/files/g%201/segments?from=0");
      expect((source as GrowingSource).indexedSeconds).toBe(2);
    } finally {
      source.close();
    }
  });

  it("opens any other relay file with plain Range requests, as before", async () => {
    vi.stubGlobal("fetch", async (input: string, init?: RequestInit) => {
      expect(String(input)).toBe("https://relay.example:8999/yarmiplay/files/p1?mode=stream");
      expect((init?.headers as Record<string, string>).Range).toBe("bytes=0-0");
      return new Response(new Uint8Array(1), { status: 206, headers: { "Content-Range": "bytes 0-0/5000" } });
    });
    const source = await openRelayFile(session, plain);
    expect(source).toBeInstanceOf(HttpSource);
    expect(isGrowing(source)).toBe(false);
    expect(source.size).toBe(5000);
  });
});

describe("offersFor", () => {
  it("still offers a local copy when the room only has a growing file of that name", async () => {
    const file = { name: "Part 1", size: 300, modified: 0, directory: "", folder: "Videos", open: async () => new File([], "Part 1") };
    const offers = await offersFor(["Part 1"], [file], [], null, async () => "hash", [growing]);
    expect(offers.map(([o]) => o.name)).toEqual(["Part 1"]);
  });
});
