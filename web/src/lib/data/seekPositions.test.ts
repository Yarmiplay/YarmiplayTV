import { describe, expect, it, beforeEach } from "vitest";
import { getSeekPosition, saveSeekPosition, clearSeekPosition } from "./seekPositions";

describe("seekPositions", () => {
  const store = new Map<string, string>();

  beforeEach(() => {
    store.clear();
    const mockStorage = {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, value),
      removeItem: (key: string) => store.delete(key),
      clear: () => store.clear(),
      length: 0,
      key: () => null,
    };
    (globalThis as unknown as { localStorage: Storage }).localStorage = mockStorage as Storage;
  });

  it("returns 0 when no position is stored", () => {
    expect(getSeekPosition("Ep01.mkv")).toBe(0);
    expect(getSeekPosition("")).toBe(0);
    expect(getSeekPosition(null)).toBe(0);
  });

  it("saves and retrieves seek positions case-insensitively", () => {
    saveSeekPosition("Episode 1.mkv", 125.5);
    expect(getSeekPosition("episode 1.mkv")).toBe(125.5);
    expect(getSeekPosition("EPISODE 1.MKV")).toBe(125.5);
  });

  it("ignores zero or negative or invalid positions", () => {
    saveSeekPosition("Ep02.mkv", 0);
    expect(getSeekPosition("Ep02.mkv")).toBe(0);

    saveSeekPosition("Ep02.mkv", -10);
    expect(getSeekPosition("Ep02.mkv")).toBe(0);

    saveSeekPosition("Ep02.mkv", NaN);
    expect(getSeekPosition("Ep02.mkv")).toBe(0);
  });

  it("clears seek positions", () => {
    saveSeekPosition("Ep03.mkv", 450);
    expect(getSeekPosition("Ep03.mkv")).toBe(450);
    clearSeekPosition("ep03.mkv");
    expect(getSeekPosition("Ep03.mkv")).toBe(0);
  });
});
