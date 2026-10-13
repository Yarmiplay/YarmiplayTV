import * as Filenames from "./filenames";
import { Constants, type FileInfo } from "./models";

/** The ways Syncplay's "your file differs" warning tells two users' files apart. */
export type FileDifference = "NAME" | "SIZE" | "DURATION";

const hasSize = (f: FileInfo) => f.size > 0 || f.sizeHash != null;
const comparableSizeHash = (f: FileInfo) => f.sizeHash ?? Filenames.hashSize(f.size);

/** Syncplay's utils.sameFilesize: an unknown size matches anything, and a hashed size matches its plain size. */
export function sameSize(a: FileInfo, b: FileInfo): boolean {
  if (!hasSize(a) || !hasSize(b)) return true;
  if (a.size > 0 && b.size > 0) return a.size === b.size;
  return comparableSizeHash(a) === comparableSizeHash(b);
}

/** Java's Math.round: halves round up, also below zero. */
const javaRound = (x: number) => Math.floor(x + 0.5);

/** Syncplay's utils.sameFileduration, on whole seconds. */
export const sameDuration = (a: FileInfo, b: FileInfo): boolean =>
  Math.abs(javaRound(a.duration) - javaRound(b.duration)) < Constants.DIFFERENT_DURATION_THRESHOLD;

/** How [a] and [b] differ, by Syncplay's checks; empty when they look like the same file. */
export function differences(a: FileInfo, b: FileInfo): Set<FileDifference> {
  const out = new Set<FileDifference>();
  if (!Filenames.same(a.name, b.name)) out.add("NAME");
  if (!sameSize(a, b)) out.add("SIZE");
  if (!sameDuration(a, b)) out.add("DURATION");
  return out;
}
