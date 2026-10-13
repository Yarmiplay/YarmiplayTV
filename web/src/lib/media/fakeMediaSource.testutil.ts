import { found, MediaSourceError, mediaItem, notFound, type Found, type MediaItem, type MediaSource, type PlayableMedia, type PlaybackReport, type ResolveResult } from "./mediaSource";
import type { SkipSegment } from "./skipSegment";

export const JF = "jellyfin:living";
export const JF2 = "jellyfin:office";
export const PX = "plex:den";

/** A server with a fixed set of files; [loose] files only match through [resolveByFilename] (FakeMediaSource.kt). */
export class FakeMediaSource implements MediaSource {
  readonly kind: string;
  readonly displayName: string;
  exactLookups = 0;
  /** The file names [findExact] was asked for, in order. */
  readonly lookedUp: string[] = [];
  looseLookups = 0;
  readonly reports: PlaybackReport[] = [];

  constructor(
    readonly key: string,
    private readonly opts: {
      exact?: Record<string, number>;
      loose?: Record<string, string>;
      lookupDelayMs?: number;
      failReports?: boolean;
      /** The opening and ending marked for each item id, for [skipSegments]. */
      markers?: Record<string, SkipSegment[]>;
    } = {},
  ) {
    this.kind = key.split(":")[0];
    const name = key.slice(key.indexOf(":") + 1);
    this.displayName = name.charAt(0).toUpperCase() + name.slice(1);
  }

  playableFor(fileName: string, size = this.opts.exact?.[fileName] ?? 0): PlayableMedia {
    return {
      itemId: `${this.key}:${fileName}`,
      url: `http://${this.key}/${fileName}`,
      fileName,
      sizeBytes: size,
      durationSeconds: 100,
      title: fileName,
      sourceKey: this.key,
      episode: null,
    };
  }

  private item(fileName: string): MediaItem {
    return mediaItem({ id: `${this.key}:${fileName}`, name: fileName, type: "MOVIE", isFolder: false, path: `/m/${fileName}`, sourceKey: this.key });
  }

  async findExact(fileName: string): Promise<Found | null> {
    this.exactLookups++;
    this.lookedUp.push(fileName);
    if (this.opts.lookupDelayMs) await new Promise((r) => setTimeout(r, this.opts.lookupDelayMs));
    const name = Object.keys(this.opts.exact ?? {}).find((k) => k.toLowerCase() === fileName.toLowerCase());
    return name === undefined ? null : found(this.item(name), this.playableFor(name), "EXACT_FILENAME");
  }

  async resolveByFilename(fileName: string): Promise<ResolveResult> {
    this.looseLookups++;
    const exact = await this.findExact(fileName);
    if (exact) return exact;
    const name = this.opts.loose?.[fileName];
    return name === undefined ? notFound(fileName, `Not in ${this.displayName}`) : found(this.item(name), this.playableFor(name), "PARSED_SEARCH");
  }

  async reportPlayback(report: PlaybackReport): Promise<void> {
    if (this.opts.failReports) throw new MediaSourceError(`${this.displayName} is down`);
    this.reports.push(report);
  }

  async skipSegments(itemId: string): Promise<SkipSegment[]> {
    return this.opts.markers?.[itemId] ?? [];
  }

  async seasonEpisodes(): Promise<PlayableMedia[]> {
    return [];
  }
  async libraries(): Promise<MediaItem[]> {
    return [];
  }
  async children(): Promise<MediaItem[]> {
    return [];
  }
  async search(): Promise<MediaItem[]> {
    return [];
  }
  async recent(): Promise<MediaItem[]> {
    return [];
  }
  async playable(item: MediaItem): Promise<PlayableMedia> {
    return this.playableFor(item.name);
  }
  imageUrl(): string | null {
    return null;
  }
}
