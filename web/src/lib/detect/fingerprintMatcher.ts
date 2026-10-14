import { HOP_SECONDS, SILENCE } from "./fingerprinter";

/** A stretch of sound two fingerprints share: points [aStart] until [aEnd] of one are [bStart] until [bEnd] of the other. */
export interface SharedRun {
  readonly aStart: number;
  readonly aEnd: number;
  readonly bStart: number;
  readonly bEnd: number;
}

export const runLength = (run: SharedRun): number => run.aEnd - run.aStart;

/** Points at most this many bits apart are the same sound. */
export const MAX_BIT_ERRORS = 6;
/**
 * A run carries on over this long a stretch that doesn't match: a point hears about 2.7 s, so a 2 s sound over the
 * song spoils about 4.5 s of points.
 */
export const MAX_GAP_SECONDS = 5.0;
/** At most this many shifts are walked, the best voted first. */
const SHIFTS = 24;
/** A value found this often in the other fingerprint (a held note, a hum) says too little to vote. */
const COMMON = 64;
/** Of a run's points, at least this share match. */
const MIN_DENSITY = 0.4;
/** A run's first and last match have at least [MIN_SUPPORT] matches among the [SUPPORT] points from them inward. */
const SUPPORT = 8;
const MIN_SUPPORT = 4;
const START_OFFSET_SECONDS = 1.1;
const END_OFFSET_SECONDS = 0.95;

/** The number of bits set in the 32 bits of [x] (Java's Integer.bitCount). */
export function bitCount(x: number): number {
  x = x - ((x >>> 1) & 0x55555555);
  x = (x & 0x33333333) + ((x >>> 2) & 0x33333333);
  x = (x + (x >>> 4)) & 0x0f0f0f0f;
  return Math.imul(x, 0x01010101) >>> 24;
}

/**
 * Finds the sound two episodes share, such as their opening song (FingerprintMatcher.kt). Values that are equal or
 * nearly so in both fingerprints vote for how far one is shifted against the other; along each well-voted shift,
 * points a few bits apart count as the same, and a run of them may skip short stretches that differ (a sound effect
 * over the song, a line of dialogue). Silence is never counted: every silence looks the same.
 *
 * The shared runs from [minPoints] to [maxPoints] long, the longest first, none overlapping another in [a]; at most
 * [limit].
 */
export function sharedRuns(a: Int32Array, b: Int32Array, minPoints: number, maxPoints: number, limit = 3): SharedRun[] {
  if (a.length === 0 || b.length === 0) return [];
  const index = new Map<number, number[]>();
  for (let j = 0; j < b.length; j++) {
    if (b[j] === SILENCE) continue;
    const at = index.get(b[j]);
    if (at) at.push(j);
    else index.set(b[j], [j]);
  }
  const votes = new Map<number, number>();
  for (let i = 0; i < a.length; i++) {
    if (a[i] === SILENCE) continue;
    for (let d = -2; d <= 2; d++) {
      const at = index.get((a[i] + d) | 0);
      if (!at || at.length > COMMON) continue;
      for (const j of at) votes.set(j - i, (votes.get(j - i) ?? 0) + 1);
    }
  }
  const gap = Math.round(MAX_GAP_SECONDS / HOP_SECONDS);
  // Shifts as the Kotlin walks them: by votes, ties in its HashMap's order (stable sorts keep it).
  const shifts = javaHashMapOrder([...votes.keys()])
    .filter((shift) => votes.get(shift)! >= 2)
    .sort((x, y) => votes.get(y)! - votes.get(x)!)
    .slice(0, SHIFTS);
  const found = shifts
    .flatMap((shift) => runsAt(a, b, shift, gap))
    .filter((run) => runLength(run) >= minPoints && runLength(run) <= maxPoints)
    .sort((x, y) => runLength(y) - runLength(x));
  const kept: SharedRun[] = [];
  for (const run of found) {
    if (kept.every((k) => overlap(k, run) <= 0)) kept.push(run);
    if (kept.length === limit) break;
  }
  return kept;
}

/**
 * Every run along [shift] (b's index minus a's). Its ends are matches with others close by: a lone chance match a few
 * seconds off doesn't stretch it.
 */
function runsAt(a: Int32Array, b: Int32Array, shift: number, gap: number): SharedRun[] {
  const from = Math.max(0, -shift);
  const n = Math.min(a.length, b.length - shift) - from;
  if (n <= 0) return [];
  const hits = new Int32Array(n + 1);
  for (let k = 0; k < n; k++) {
    const x = a[from + k];
    const y = b[from + k + shift];
    const hit = x !== SILENCE && y !== SILENCE && bitCount(x ^ y) <= MAX_BIT_ERRORS;
    hits[k + 1] = hits[k] + (hit ? 1 : 0);
  }
  const clamp = (v: number) => Math.min(Math.max(v, 0), n);
  const hit = (k: number) => hits[k + 1] > hits[k];
  const count = (lo: number, hi: number) => hits[clamp(hi)] - hits[clamp(lo)];
  const runs: SharedRun[] = [];
  let k = 0;
  while (k < n) {
    if (!hit(k)) {
      k++;
      continue;
    }
    let last = k;
    let next = k + 1;
    while (next < n && next - last <= gap) {
      if (hit(next)) last = next;
      next++;
    }
    let start = k;
    let end = last;
    while (start <= end && (!hit(start) || count(start, start + SUPPORT) < MIN_SUPPORT)) start++;
    while (end >= start && (!hit(end) || count(end - SUPPORT + 1, end + 1) < MIN_SUPPORT)) end--;
    if (end >= start && count(start, end + 1) >= (end + 1 - start) * MIN_DENSITY) {
      runs.push({ aStart: from + start, aEnd: from + end + 1, bStart: from + start + shift, bEnd: from + end + 1 + shift });
    }
    k = last + 1;
  }
  return runs;
}

/**
 * Seconds into the fingerprinted sound at which a run starting at [point] starts: a point hears about 2.7 s from its
 * own time, so it matches a little before the shared sound begins.
 */
export const startSeconds = (point: number): number => point * HOP_SECONDS + START_OFFSET_SECONDS;

/** Seconds into the fingerprinted sound at which a run ending before [point] ends: a point hears a few seconds on. */
export const endSeconds = (point: number): number => point * HOP_SECONDS + END_OFFSET_SECONDS;

const overlap = (x: SharedRun, y: SharedRun): number => Math.min(x.aEnd, y.aEnd) - Math.max(x.aStart, y.aStart);

/**
 * [keys] (Ints, in the order first put) in the order a java.util.HashMap holding them iterates: by bucket, and within
 * a bucket in the order put. Which of equally voted shifts the Kotlin walks first depends on it, so the same
 * fingerprints give the same runs here.
 */
function javaHashMapOrder(keys: number[]): number[] {
  let capacity = 16;
  while (keys.length > capacity * 0.75) capacity *= 2;
  const bucket = (h: number) => (h ^ (h >>> 16)) & (capacity - 1);
  return keys
    .map((key, order) => ({ key, order, at: bucket(key) }))
    .sort((x, y) => x.at - y.at || x.order - y.order)
    .map((e) => e.key);
}
