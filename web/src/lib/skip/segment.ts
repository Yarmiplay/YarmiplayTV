/** An anime episode's opening or ending; also how it's named on the Skip button ("Skip opening"). */
export type SkipKind = "opening" | "ending";

/** The parts, in the order sources are looked through. */
export const SKIP_KINDS: readonly SkipKind[] = ["opening", "ending"];

/** Where an opening or ending is in a file, in seconds. */
export interface SkipSegment {
  kind: SkipKind;
  start: number;
  end: number;
}

/** A chapter of the playing file, in seconds. */
export interface Chapter {
  start: number;
  end: number;
  title: string;
}
