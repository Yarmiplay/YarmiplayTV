import { describe, expect, it } from "vitest";
import { chapterBoundaries, chapterTitleAt } from "./chapterMarks";

describe("chapterBoundaries", () => {
  it("a file with no chapters has no marks", () => {
    expect(chapterBoundaries([], 1440)).toEqual([]);
    expect(chapterBoundaries([{ start: 0, title: "Cold open" }], 0)).toEqual([]);
    expect(chapterBoundaries([{ start: 0, title: "Cold open" }], Number.NaN)).toEqual([]);
    expect(chapterTitleAt([], 10)).toBeNull();
  });

  it("drops out-of-range times and names an untitled chapter", () => {
    const marks = chapterBoundaries(
      [
        { start: 80, title: "Later" },
        { start: 10, title: "Early" },
        { start: -1, title: "Before" },
        { start: 150, title: "After" },
        { start: Number.NaN, title: "Bad" },
        { start: Number.POSITIVE_INFINITY, title: "Infinite" },
        { start: 40, title: "   " },
        { start: 40.02, title: null },
      ],
      100,
    );
    expect(marks.map((m) => m.time)).toEqual([10, 40, 80]);
    expect(marks.map((m) => m.label)).toEqual(["Early", "Chapter 2", "Later"]);
    expect(chapterTitleAt(marks, 9)).toBeNull();
    expect(chapterTitleAt(marks, 10)).toBe("Early");
    expect(chapterTitleAt(marks, 39)).toBe("Early");
    expect(chapterTitleAt(marks, 40)).toBe("Chapter 2");
    expect(chapterTitleAt(marks, 100)).toBe("Later");
  });

  it("duplicate starts share one mark and keep both names", () => {
    const marks = chapterBoundaries(
      [
        { start: 10, title: "Intro" },
        { start: 10.02, title: "Intro" },
        { start: 10.03, title: "" },
        { start: 10.04, title: "Cold open" },
        { start: 30, title: "Part A" },
      ],
      100,
    );
    expect(marks.map((m) => m.time)).toEqual([10, 30]);
    expect(marks[0].label).toBe("Intro · Cold open");
    expect(marks[1].label).toBe("Part A");
  });

  it("replacing the file's chapters does not keep the previous names", () => {
    const first = chapterBoundaries(
      [
        { start: 1, title: "Old" },
        { start: 5, title: "Older" },
      ],
      10,
    );
    const next = chapterBoundaries([{ start: 2, title: "New" }], 10);
    expect(chapterTitleAt(first, 3)).toBe("Old");
    expect(chapterTitleAt(next, 3)).toBe("New");
    expect(chapterTitleAt(chapterBoundaries([], 10), 3)).toBeNull();
  });
});
