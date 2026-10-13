import {
  fileNameOf,
  MediaSourceError,
  notFound,
  type Found,
  type MediaItem,
  type MediaSource,
  type PlayableMedia,
  type PlaybackReport,
  type ResolveResult,
} from "./mediaSource";
import type { SkipSegment } from "./skipSegment";

/** [CompositeMediaSource]'s key and kind. */
export const COMPOSITE_KEY = "all";

/**
 * Browses several servers as one (CompositeMediaSource.kt): libraries side by side, searches and shortcuts merged, and
 * each item's own calls routed back to the server it came from (by [MediaItem.sourceKey]).
 */
export class CompositeMediaSource implements MediaSource {
  readonly key = COMPOSITE_KEY;
  readonly kind = COMPOSITE_KEY;

  constructor(readonly sources: MediaSource[]) {
    if (sources.length === 0) throw new Error("A combined view needs at least one server");
  }

  get displayName(): string {
    return this.sources.map((s) => s.displayName).join(" & ");
  }

  sourceOf(item: Pick<MediaItem, "sourceKey">): MediaSource | null {
    return this.sources.find((s) => s.key === item.sourceKey) ?? null;
  }

  private route(item: MediaItem): MediaSource {
    const source = this.sourceOf(item);
    if (!source) throw new MediaSourceError(`${item.name} belongs to a server that isn't connected`);
    return source;
  }

  /** Runs [block] on every source at once; a failing server is skipped unless all of them fail. */
  private async all<T>(block: (s: MediaSource) => Promise<T[]>): Promise<T[][]> {
    const results = await Promise.allSettled(this.sources.map((s) => block(s)));
    if (results.every((r) => r.status === "rejected")) throw (results[0] as PromiseRejectedResult).reason;
    return results.map((r) => (r.status === "fulfilled" ? r.value : []));
  }

  async libraries(): Promise<MediaItem[]> {
    return (await this.all((s) => s.libraries())).flat();
  }

  children(parent: MediaItem): Promise<MediaItem[]> {
    return this.route(parent).children(parent);
  }

  async search(query: string, limit = 60): Promise<MediaItem[]> {
    return merge(await this.all((s) => s.search(query, limit))).slice(0, limit);
  }

  async recent(limit = 24): Promise<MediaItem[]> {
    return merge(await this.all((s) => s.recent(limit))).slice(0, limit);
  }

  playable(item: MediaItem): Promise<PlayableMedia> {
    return this.route(item).playable(item);
  }

  async resolveByFilename(fileName: string): Promise<ResolveResult> {
    for (const source of this.sources) {
      const r = await source.resolveByFilename(fileName).catch(() => null);
      if (r?.kind === "found") return r;
    }
    return notFound(fileName, `Not found in ${this.displayName}`);
  }

  async findExact(fileName: string): Promise<Found | null> {
    for (const source of this.sources) {
      const r = await source.findExact(fileName).catch(() => null);
      if (r) return r;
    }
    return null;
  }

  async reportPlayback(_report: PlaybackReport): Promise<void> {
    throw new MediaSourceError("Report to the item's own server, not the combined view");
  }

  async skipSegments(_itemId: string): Promise<SkipSegment[]> {
    return [];
  }

  async seasonEpisodes(_itemId: string): Promise<PlayableMedia[]> {
    return [];
  }

  imageUrl(item: MediaItem, maxWidth = 400): string | null {
    return this.sourceOf(item)?.imageUrl(item, maxWidth) ?? null;
  }
}

/** Interleaves the servers' lists; the same file on two servers is shown once (it plays and reports the same either way). */
function merge(lists: MediaItem[][]): MediaItem[] {
  const out: MediaItem[] = [];
  const files = new Set<string>();
  const longest = Math.max(0, ...lists.map((l) => l.length));
  for (let i = 0; i < longest; i++) {
    for (const list of lists) {
      const item = list[i];
      if (item === undefined) continue;
      const file = fileNameOf(item)?.toLowerCase() ?? null;
      if (file === null || !files.has(file)) {
        if (file !== null) files.add(file);
        out.push(item);
      }
    }
  }
  return out;
}
