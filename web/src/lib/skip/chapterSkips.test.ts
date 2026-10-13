import { describe, expect, it } from "vitest";
import { chapterSkips } from "./chapterSkips";
import type { Chapter, SkipSegment } from "./segment";

// Ported from ChapterSkipsTest.kt. The apps' chapters have only a start; each here ends where the next starts.

const opening = (start: number, end: number): SkipSegment => ({ kind: "opening", start, end });
const ending = (start: number, end: number): SkipSegment => ({ kind: "ending", start, end });

/** Chapters from (title, start) pairs, each ending where the next starts (the last at [duration]). */
const chapters = (duration: number, ...list: [string, number][]): Chapter[] =>
  list.map(([title, start], i) => ({ title, start, end: list[i + 1]?.[1] ?? duration }));

describe("chapterSkips", () => {
  it("the usual chapter names mark the opening and ending, each up to the next chapter", () => {
    const list = chapters(1440, ["Intro", 0], ["Part A", 90], ["Ending Credits", 1330], ["Next Episode", 1420]);
    expect(chapterSkips(list, 1440)).toEqual([opening(0, 90), ending(1330, 1420)]);
    for (const name of ["OP", "Opening", "OP2", "Opening Song", "opening-theme", "オープニング"]) {
      expect(chapterSkips(chapters(1440, ["Avant", 0], [name, 30], ["Part A", 120]), 1440), name).toEqual([opening(30, 120)]);
    }
    // An ending at the very end runs to the end of the file.
    expect(chapterSkips(chapters(1440, ["Part B", 700], ["ED", 1350]), 1440)).toEqual([ending(1350, 1440)]);
  });

  it("other chapters, and ones too short or too long to be an opening, aren't taken", () => {
    expect(chapterSkips(chapters(1440, ["Chapter 1", 0], ["Chapter 2", 600]), 1440)).toEqual([]);
    expect(chapterSkips(chapters(1440, ["Opening", 0], ["Part A", 4]), 1440)).toEqual([]);
    expect(chapterSkips(chapters(1440, ["Opening", 0], ["Part A", 900]), 1440)).toEqual([]);
    expect(chapterSkips(chapters(1440, ["", 0], ["Opening of the war", 30], ["x", 120]), 1440)).toEqual([]);
  });

  it("chapters out of order are taken in order", () => {
    const list: Chapter[] = [
      { title: "Part A", start: 90, end: 1440 },
      { title: "Prologue", start: 0, end: 20 },
      { title: "Opening", start: 20, end: 90 },
    ];
    expect(chapterSkips(list, 1440)).toEqual([opening(20, 90)]);
  });
});
