import { describe, expect, it } from "vitest";
import { MemoryCacheStore } from "./cacheStore";
import { FingerprintCache, decodeFingerprint, encodeFingerprint, keyOf } from "./fingerprintCache";
import golden from "./kotlinGolden.json";

// The fingerprint cache, in the apps' formats: kotlinGolden.json holds the files FingerprintCache.kt wrote.

const hex = (bytes: Uint8Array) => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
const points = Int32Array.from([1, -2, 2147483647, -2147483648]);

describe("FingerprintCache", () => {
  it("knows an episode by its name and size, else its address, as the apps do", () => {
    expect(keyOf("Show - 01.MKV", 1001, "file:///x")).toBe(golden.key1);
    expect(keyOf("", 0, "http://a/b?c=1")).toBe(golden.key2);
    expect(keyOf("show - 01.mkv", 1001, "http://elsewhere/1")).toBe(golden.key1);
  });

  it("writes fingerprints in the apps' YFP1 layout", async () => {
    expect(hex(encodeFingerprint({ start: 12.5, points }))).toBe(golden.yfp1);
    const store = new MemoryCacheStore();
    await new FingerprintCache(store).saveFingerprint("k", "OPENING", { start: 12.5, points });
    expect(hex((await store.read("fp/k-op.bin"))!)).toBe(golden.yfp1);
    const read = await new FingerprintCache(store).fingerprint("k", "OPENING");
    expect(read?.start).toBe(12.5);
    expect(Array.from(read!.points)).toEqual(Array.from(points));
    expect(await new FingerprintCache(store).fingerprint("k", "ENDING")).toBeNull();
  });

  it("writes observations as the apps do, and reads them back", async () => {
    const store = new MemoryCacheStore();
    const cache = new FingerprintCache(store);
    await cache.observe("k", "other", "OPENING", [{ start: 20.0, end: 110.5 }]);
    await cache.observe("k", "third", "ENDING", []);
    expect(new TextDecoder().decode((await store.read("obs/k.json"))!)).toBe(golden.obsJson);
    expect(await cache.observations("k", "OPENING")).toEqual({ other: [{ start: 20, end: 110.5 }] });
    expect(await cache.observations("k", "ENDING")).toEqual({ third: [] });
    expect(await cache.observations("nobody", "OPENING")).toEqual({});
  });

  it("observations made at once are all kept", async () => {
    const cache = new FingerprintCache(new MemoryCacheStore());
    await Promise.all(["a", "b", "c", "d"].map((other) => cache.observe("k", other, "OPENING", [{ start: 1, end: 2 }])));
    expect(Object.keys(await cache.observations("k", "OPENING")).sort()).toEqual(["a", "b", "c", "d"]);
  });

  it("forgets files it can't read", async () => {
    const store = new MemoryCacheStore();
    const cache = new FingerprintCache(store);
    const cut = encodeFingerprint({ start: 0, points }).slice(0, 20);
    await store.write("fp/bad-op.bin", cut);
    await store.write("obs/bad.json", new TextEncoder().encode("{not json"));
    expect(await cache.fingerprint("bad", "OPENING")).toBeNull();
    expect(await cache.observations("bad", "OPENING")).toEqual({});
    expect(store.paths()).toEqual([]);
    expect(decodeFingerprint(new Uint8Array(16))).toBeNull();
  });

  it("keeps under its size, the least recently used out first", async () => {
    const store = new MemoryCacheStore();
    const big = { start: 0, points: new Int32Array(250) }; // 1016 bytes each
    const before = new FingerprintCache(store, 4000);
    for (const key of ["a", "b", "c", "d", "e"]) await before.saveFingerprint(key, "OPENING", big);
    // Used since: "a" is now the most recent of them.
    await before.fingerprint("a", "OPENING");
    // A later visit prunes once, before its first save: down to 3000 bytes.
    const after = new FingerprintCache(store, 4000);
    await after.saveFingerprint("f", "ENDING", big);
    expect(store.paths().sort()).toEqual(["fp/a-op.bin", "fp/e-op.bin", "fp/f-ed.bin"]);
  });
});
