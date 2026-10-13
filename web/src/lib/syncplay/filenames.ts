import { sha256Hex } from "./hash";

const STRIP = /[-~_.[\](): ]/g;
const HASH = /^[0-9a-f]{12}$/;

/** What a Syncplay client in "don't send" privacy mode reports instead of its filename. */
export const HIDDEN = "**Hidden filename**";

export const isUrl = (path: string): boolean => path.includes("://");

const afterLast = (s: string, c: string) => s.slice(s.lastIndexOf(c) + 1);

function decode(s: string): string {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

/** Syncplay's utils.stripfilename. */
export function strip(filename: string, stripUrl: boolean): string {
  let name = decode(filename);
  if (stripUrl) name = decode(afterLast(name, "/"));
  return name.replace(STRIP, "");
}

const equalsIgnoreCase = (a: string, b: string) =>
  a.length === b.length && (a.toUpperCase() === b.toUpperCase() || a.toLowerCase() === b.toLowerCase());

/**
 * Syncplay's utils.sameFilename: ignores case, separators/brackets and URL-encoding, matches a name sent hashed
 * ("send hashed" privacy mode) against the plain one, and a hidden name against anything.
 */
export function same(a: string | null | undefined, b: string | null | undefined): boolean {
  if (a == null || b == null) return false;
  if (a === HIDDEN || b === HIDDEN) return true;
  const stripUrl = isUrl(a) !== isUrl(b);
  if (equalsIgnoreCase(strip(a, stripUrl), strip(b, stripUrl))) return true;
  return (isHash(a) && a === hash(b, stripUrl)) || (isHash(b) && b === hash(a, stripUrl));
}

/** Syncplay's utils.hashFilename: what "send hashed" privacy mode sends instead of the filename. */
export const hash = (filename: string, stripUrl = false): string =>
  sha256Hex(strip(filename, stripUrl || isUrl(filename))).slice(0, 12);

/** Syncplay's utils.hashFilesize: what "send hashed" privacy mode sends instead of the size. */
export const hashSize = (size: number): string => sha256Hex(String(size)).slice(0, 12);

/** Whether [text] has the shape of [hash] or [hashSize]'s output. */
export const isHash = (text: string): boolean => HASH.test(text);

/** Filename without directories, for either Windows or POSIX paths. */
export const baseName = (path: string): string => afterLast(afterLast(path, "/"), "\\");
