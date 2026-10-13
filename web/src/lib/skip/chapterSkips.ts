import type { Chapter, SkipKind, SkipSegment } from "./segment";

const opening = /^(op|opening|intro|introduction|op ?\d+|opening ?\d+|opening (song|theme|credits|titles|sequence)|オープニング)$/u;
const ending = /^(ed|ending|outro|credits|end credits|closing credits|ed ?\d+|ending ?\d+|ending (song|theme|credits|titles|sequence)|エンディング)$/u;
const separators = /[^\p{L}\p{N}]+/gu;

const MIN_SECONDS = 10;
const MAX_OPENING_SECONDS = 240;
const MAX_ENDING_SECONDS = 360;

/**
 * The opening and ending a file's own chapters mark, by their names: "Opening", "OP", "Intro", "Opening Song",
 * "OP2"… and "Ending", "ED", "Credits", "Outro", "Ending Credits"… (also オープニング and エンディング). Each runs to the
 * next chapter (or the end of the file). One much too short or too long to be one isn't taken.
 */
export function chapterSkips(chapters: readonly Chapter[], duration: number): SkipSegment[] {
  const sorted = [...chapters].sort((a, b) => a.start - b.start);
  const out: SkipSegment[] = [];
  sorted.forEach((chapter, i) => {
    const name = (chapter.title ?? "").toLowerCase().replace(separators, " ").trim();
    let kind: SkipKind;
    if (opening.test(name)) kind = "opening";
    else if (ending.test(name)) kind = "ending";
    else return;
    // The next chapter's start, as the apps have it; the last runs to the end of the file (or its own end, if that's all there is).
    const next = sorted[i + 1]?.start;
    const end = next ?? (duration > 0 ? duration : chapter.end > chapter.start ? chapter.end : null);
    if (end == null) return;
    const length = end - chapter.start;
    const longest = kind === "opening" ? MAX_OPENING_SECONDS : MAX_ENDING_SECONDS;
    if (length < MIN_SECONDS || length > longest) return;
    out.push({ kind, start: chapter.start, end });
  });
  return out;
}
