import { describe, expect, it } from "vitest";
import * as PlaylistEdits from "./playlistEdits";
import { TrustedDomains } from "./playlistEdits";

// Ported from PlaylistEditsTest.kt.
const list = ["a.mkv", "b.mkv", "c.mkv", "d.mkv", "e.mkv"];

/** A seeded random in [0, 1), so shuffles repeat. */
function seeded(seed: number) {
  let s = seed >>> 0;
  return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 0x100000000);
}

describe("PlaylistEdits", () => {
  it("new entries skip blanks, repeats and what's already there", () => {
    expect(PlaylistEdits.newEntries(list, [" ", "b.mkv", "f.mkv", " f.mkv ", "https://example.org/x.mp4"])).toEqual([
      "f.mkv",
      "https://example.org/x.mp4",
    ]);
  });

  it("insert places entries before an index, or at the end", () => {
    const added = ["x.mkv", "y.mkv"];
    expect(PlaylistEdits.insert(list, added, 0)).toEqual([...added, ...list]);
    expect(PlaylistEdits.insert(list, added, 2)).toEqual(["a.mkv", "b.mkv", "x.mkv", "y.mkv", "c.mkv", "d.mkv", "e.mkv"]);
    expect(PlaylistEdits.insert(list, added, null)).toEqual([...list, ...added]);
    expect(PlaylistEdits.insert(list, added, 99)).toEqual([...list, ...added]);
    expect(PlaylistEdits.insert(list, added, -1)).toEqual([...added, ...list]);
  });

  it("shift moves a selection as a block and stops at the ends", () => {
    expect(PlaylistEdits.shift(list, new Set([1, 2]), -1)).toEqual([["b.mkv", "c.mkv", "a.mkv", "d.mkv", "e.mkv"], new Set([0, 1])]);
    expect(PlaylistEdits.shift(list, new Set([1, 3]), 1)).toEqual([["a.mkv", "c.mkv", "b.mkv", "e.mkv", "d.mkv"], new Set([2, 4])]);
    expect(PlaylistEdits.shift(list, new Set([0, 2]), -1)).toEqual([list, new Set([0, 2])]);
    expect(PlaylistEdits.shift(list, new Set([4]), 1)).toEqual([list, new Set([4])]);
  });

  it("remove and move", () => {
    expect(PlaylistEdits.remove(list, new Set([0, 2]))).toEqual(["b.mkv", "d.mkv", "e.mkv"]);
    expect(PlaylistEdits.move(list, 0, 3)).toEqual(["b.mkv", "c.mkv", "d.mkv", "a.mkv", "e.mkv"]);
    expect(PlaylistEdits.move(list, 0, 9)).toEqual(list);
  });

  it("shuffling what's left keeps the entries up to the current one", () => {
    const remaining = PlaylistEdits.shuffle(list, 1, false, seeded(7));
    expect(remaining.slice(0, 2)).toEqual(list.slice(0, 2));
    expect(new Set(remaining)).toEqual(new Set(list));
    const entire = PlaylistEdits.shuffle(list, 1, true, seeded(7));
    expect(new Set(entire)).toEqual(new Set(list));
    expect(entire).toHaveLength(list.length);
  });

  it("sorting goes A to Z ignoring case, with numbers in number order", () => {
    const messy = [
      "Show - 10.mkv",
      "show - 2.mkv",
      "Another Show S01E01.mkv",
      "Show - 1.mkv",
      "Show - 02v2.mkv",
      "https://youtube.com/watch?v=x",
      "Show - 001.mkv",
    ];
    expect(PlaylistEdits.sortAlphabetically(messy)).toEqual([
      "Another Show S01E01.mkv",
      "https://youtube.com/watch?v=x",
      "Show - 001.mkv",
      "Show - 1.mkv",
      "show - 2.mkv",
      "Show - 02v2.mkv",
      "Show - 10.mkv",
    ]);
    expect(PlaylistEdits.sortAlphabetically([])).toEqual([]);
    // Long runs of digits don't overflow.
    expect(PlaylistEdits.sortAlphabetically(["a 100000000000000000000", "a 99999999999999999999"])).toEqual([
      "a 99999999999999999999",
      "a 100000000000000000000",
    ]);
  });

  it("playlist files are one entry per line", () => {
    expect(PlaylistEdits.format(["a.mkv", "https://youtu.be/x"])).toBe("a.mkv\nhttps://youtu.be/x\n");
    expect(PlaylistEdits.parse("\r\n a.mkv \r\n\r\nhttps://youtu.be/x\n")).toEqual(["a.mkv", "https://youtu.be/x"]);
  });
});

describe("TrustedDomains", () => {
  it("work like Syncplay's", () => {
    const domains = [...TrustedDomains.DEFAULT, "example.org/videos/"];
    expect(TrustedDomains.isTrusted("https://www.youtube.com/watch?v=x", domains)).toBe(true);
    expect(TrustedDomains.isTrusted("https://youtu.be/x", domains)).toBe(true);
    expect(TrustedDomains.isTrusted("HTTP://YOUTUBE.COM/watch", domains)).toBe(true);
    expect(TrustedDomains.isTrusted("https://example.org/videos/a.mp4", domains)).toBe(true);
    expect(TrustedDomains.isTrusted("https://example.org/videos", domains)).toBe(true);
    expect(TrustedDomains.isTrusted("https://example.org/other/a.mp4", domains)).toBe(false);
    expect(TrustedDomains.isTrusted("https://youtube.com.evil.example/x", domains)).toBe(false);
    expect(TrustedDomains.isTrusted("https://m.youtube.com/x", domains)).toBe(false);
    expect(TrustedDomains.isTrusted("ftp://youtube.com/x", domains)).toBe(false);
    expect(TrustedDomains.isTrusted("https://youtube.com/x", [])).toBe(false);
  });

  it("the domain to trust drops www", () => {
    expect(TrustedDomains.domainOf("https://www.example.org/a.mp4")).toBe("example.org");
    expect(TrustedDomains.domainOf("http://cdn.example.org:8080/a.mp4")).toBe("cdn.example.org");
    expect(TrustedDomains.domainOf("not a url")).toBeNull();
  });
});
