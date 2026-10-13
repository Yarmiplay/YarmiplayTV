import { baseName, normalize } from "./fileNames";
import type { MatchKind } from "./mediaSource";

export interface IndexEntry {
  id: string;
  fileName: string;
}

const groupBy = (entries: IndexEntry[], key: (e: IndexEntry) => string) => {
  const map = new Map<string, IndexEntry[]>();
  for (const e of entries) {
    const k = key(e);
    const list = map.get(k);
    if (list) list.push(e);
    else map.set(k, [e]);
  }
  return map;
};

/**
 * Basename index of every video file on a server, so a Syncplay playlist entry can be found without a search
 * (FilenameIndex.kt). Each source supplies the (item id, file name) pairs; this keeps the lookups and throttling.
 */
export class FilenameIndex {
  private byName = new Map<string, IndexEntry[]>();
  private byNormalized = new Map<string, IndexEntry[]>();
  private builtAt = 0;
  /** Refreshes run one at a time: a second one waits for the first, then usually finds the index fresh. */
  private lock: Promise<void> = Promise.resolve();

  constructor(
    private readonly clock: () => number = Date.now,
    private readonly minRefreshMs = 30_000,
  ) {}

  get isBuilt(): boolean {
    return this.builtAt !== 0;
  }

  get size(): number {
    let n = 0;
    for (const list of this.byName.values()) n += list.length;
    return n;
  }

  /** Rebuilds from [load], at most once per [minRefreshMs] unless [force]d. Throws what [load] throws. */
  refresh(force: boolean, load: () => Promise<IndexEntry[]>): Promise<void> {
    const run = this.lock.then(async () => {
      if (!force && this.builtAt !== 0 && this.clock() - this.builtAt < this.minRefreshMs) return;
      const entries = await load();
      this.byName = groupBy(entries, (e) => e.fileName.toLowerCase());
      this.byNormalized = groupBy(entries, (e) => normalize(e.fileName));
      this.builtAt = this.clock();
    });
    this.lock = run.catch(() => {});
    return run;
  }

  /** Entries whose file name equals [name], ignoring case. */
  exact(name: string): IndexEntry[] {
    return this.byName.get(baseName(name).toLowerCase()) ?? [];
  }

  /** Exact matches, else Syncplay-normalized ones; null when neither exists. */
  lookup(name: string): { entries: IndexEntry[]; kind: MatchKind } | null {
    const exact = this.exact(name);
    if (exact.length > 0) return { entries: exact, kind: "EXACT_FILENAME" };
    const normalized = this.byNormalized.get(normalize(name));
    return normalized && normalized.length > 0 ? { entries: normalized, kind: "NORMALIZED_FILENAME" } : null;
  }
}
