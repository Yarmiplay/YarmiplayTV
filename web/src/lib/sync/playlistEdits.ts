import * as Filenames from "../syncplay/filenames";

/** Edits of a shared playlist, made the way desktop Syncplay makes them (SharedPlaylist.kt's PlaylistEdits). */

/** The [files] that aren't in [playlist] yet (compared like Syncplay compares filenames), in order and without repeats. */
export function newEntries(playlist: string[], files: string[]): string[] {
  const added: string[] = [];
  for (const file of files.map((f) => f.trim()).filter((f) => f.length > 0)) {
    if (!playlist.some((p) => Filenames.same(p, file)) && !added.some((a) => Filenames.same(a, file))) added.push(file);
  }
  return added;
}

/** [playlist] with [entries] placed before index [at], or at the end when [at] is null or past it. */
export function insert(playlist: string[], entries: string[], at: number | null): string[] {
  const index = Math.min(Math.max(at ?? playlist.length, 0), playlist.length);
  return [...playlist.slice(0, index), ...entries, ...playlist.slice(index)];
}

export const remove = (playlist: string[], indices: Set<number>): string[] => playlist.filter((_, i) => !indices.has(i));

/**
 * Moves the [selected] entries one place up ([delta] -1) or down (+1), keeping their order. Returns the new playlist
 * and the entries' new indices; nothing moves when one of them is already at that end.
 */
export function shift(playlist: string[], selected: Set<number>, delta: -1 | 1): [string[], Set<number>] {
  const sorted = [...selected].filter((i) => i >= 0 && i < playlist.length).sort((a, b) => a - b);
  if (sorted.length === 0 || (delta < 0 && sorted[0] === 0) || (delta > 0 && sorted[sorted.length - 1] === playlist.length - 1)) {
    return [playlist, selected];
  }
  const list = [...playlist];
  for (const i of delta < 0 ? sorted : [...sorted].reverse()) list.splice(i + delta, 0, ...list.splice(i, 1));
  return [list, new Set(sorted.map((i) => i + delta))];
}

export function move(playlist: string[], from: number, to: number): string[] {
  const inside = (i: number) => i >= 0 && i < playlist.length;
  if (!inside(from) || !inside(to) || from === to) return playlist;
  const list = [...playlist];
  list.splice(to, 0, ...list.splice(from, 1));
  return list;
}

/** A Fisher–Yates shuffle with [random] in [0, 1). */
function shuffled<T>(items: T[], random: () => number): T[] {
  const out = [...items];
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

/** "Shuffle remaining" keeps everything up to the current entry in place; [entire] shuffles all of it. */
export function shuffle(playlist: string[], index: number | null, entire: boolean, random: () => number = Math.random): string[] {
  if (entire || index === null) return shuffled(playlist, random);
  const pivot = index + 1;
  return [...playlist.slice(0, pivot), ...shuffled(playlist.slice(pivot), random)];
}

const isDigit = (c: string) => c >= "0" && c <= "9";
/** One character in lower case, as Kotlin's lowercaseChar (which never makes one character two). */
const lower = (c: string) => {
  const l = c.toLowerCase();
  return l.length === 1 ? l : c;
};
const compareCodeUnits = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

/** Names compared ignoring case, with each run of digits compared as the number it is. */
export function naturalOrder(a: string, b: string): number {
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (isDigit(a[i]) && isDigit(b[j])) {
      const startA = i;
      const startB = j;
      while (i < a.length && isDigit(a[i])) i++;
      while (j < b.length && isDigit(b[j])) j++;
      const numberA = a.slice(startA, i).replace(/^0+/, "");
      const numberB = b.slice(startB, j).replace(/^0+/, "");
      if (numberA.length !== numberB.length) return numberA.length - numberB.length;
      const c = compareCodeUnits(numberA, numberB);
      if (c !== 0) return c;
    } else {
      const c = compareCodeUnits(lower(a[i]), lower(b[j]));
      if (c !== 0) return c;
      i++;
      j++;
    }
  }
  return a.length - i - (b.length - j);
}

/** [playlist] from A to Z: case doesn't matter, and numbers go in number order ("Episode 2" before "Episode 10"). */
export const sortAlphabetically = (playlist: string[]): string[] =>
  [...playlist].sort((a, b) => naturalOrder(a, b) || compareCodeUnits(a, b));

/** Syncplay's playlist files: one entry per line. */
export const parse = (text: string): string[] =>
  text
    .split(/\r\n|\n|\r/)
    .map((l) => l.trim())
    .filter((l) => l.length > 0);

export const format = (playlist: string[]): string => playlist.join("\n") + "\n";

/** Syncplay's trusted domains: URLs the room may switch everyone to without asking. */
export const TrustedDomains = {
  DEFAULT: ["youtube.com", "youtu.be"],

  /** Port of Syncplay's isURITrusted: an entry is a domain, optionally followed by a path. */
  isTrusted(url: string, domains: string[]): boolean {
    const candidate = `${url}/`.toLowerCase();
    return ["http://www.", "https://www.", "http://", "https://"].some((protocol) =>
      domains.some((domain) => domain.trim() !== "" && candidate.startsWith(`${protocol}${domain.trim().replace(/\/+$/, "")}/`.toLowerCase())),
    );
  },

  /** The host of [url] without "www.", the entry "Trust this domain" adds. */
  domainOf(url: string): string | null {
    try {
      const host = new URL(url).hostname.replace(/^www\./, "");
      return host || null;
    } catch {
      return null;
    }
  },
};
