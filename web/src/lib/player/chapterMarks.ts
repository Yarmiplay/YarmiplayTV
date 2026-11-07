/** One embedded chapter a seek bar can draw, and the name to show while pointing there. */
export interface ChapterBoundary {
  time: number;
  label: string;
}

/** Near-duplicate starts, such as a chapter listed twice, are one mark. Matches the apps. */
const SAME_CHAPTER_BOUNDARY_SECONDS = 0.05;
const TIME_SLOP = 0.001;

/**
 * Chapters worth a mark on a bar of [duration] seconds.
 * Out-of-range, non-finite and blank files produce none. Times within a twentieth of a second of the same start
 * share one mark; their names are joined. A missing title becomes "Chapter N". The result is in time order.
 */
export function chapterBoundaries(
  chapters: readonly { start: number; title?: string | null }[],
  duration: number,
): ChapterBoundary[] {
  if (!Number.isFinite(duration) || duration <= 0) return [];
  const kept: { time: number; title: string | null }[] = [];
  for (const chapter of chapters) {
    const time = chapter.start;
    if (!Number.isFinite(time) || time < -TIME_SLOP || time > duration + TIME_SLOP) continue;
    const at = time < 0 ? 0 : time > duration ? duration : time;
    const title = chapter.title?.trim() ?? "";
    kept.push({ time: at, title: title.length > 0 ? title : null });
  }
  kept.sort((a, b) => a.time - b.time);
  const merged: { time: number; titles: string[] }[] = [];
  for (const chapter of kept) {
    const last = merged[merged.length - 1];
    if (last && chapter.time - last.time <= SAME_CHAPTER_BOUNDARY_SECONDS) {
      if (chapter.title && !last.titles.includes(chapter.title)) last.titles.push(chapter.title);
    } else {
      merged.push({ time: chapter.time, titles: chapter.title ? [chapter.title] : [] });
    }
  }
  return merged.map((mark, index) => ({
    time: mark.time,
    label: mark.titles.length === 0 ? `Chapter ${index + 1}` : mark.titles.join(" · "),
  }));
}

/** The chapter that has started by [time], or null when [time] is before the first one. */
export function chapterTitleAt(boundaries: readonly ChapterBoundary[], time: number): string | null {
  if (!Number.isFinite(time) || boundaries.length === 0) return null;
  let label: string | null = null;
  for (const mark of boundaries) {
    if (mark.time <= time) label = mark.label;
    else break;
  }
  return label;
}
