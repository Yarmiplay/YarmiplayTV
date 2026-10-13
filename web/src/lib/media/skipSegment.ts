/** A part of an episode that can be skipped: its opening or its ending (SkipSegment.kt). Times are in seconds. */
export interface SkipSegment {
  kind: SkipKind;
  start: number;
  end: number;
}

/** OPENING's label is "opening", ENDING's "ending", as the apps show them. */
export type SkipKind = "OPENING" | "ENDING";

export const skipLabel = (kind: SkipKind): string => (kind === "OPENING" ? "opening" : "ending");

/** The segments that make sense (end after start), earliest first. */
export const sortedSegments = (segments: SkipSegment[]): SkipSegment[] =>
  segments.filter((s) => s.end > s.start).sort((a, b) => a.start - b.start);
