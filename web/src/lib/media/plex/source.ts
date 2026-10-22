import * as FileNames from "../fileNames";
import { FilenameIndex, type IndexEntry } from "../filenameIndex";
import type { Query } from "../http";
import {
  displayTitle,
  fileNameOf,
  found,
  MediaSourceError,
  mediaItem,
  messageOf,
  notFound,
  type Found,
  type MatchKind,
  type MediaItem,
  type MediaItemType,
  type MediaSource,
  type PlayableMedia,
  type PlaybackReport,
  type ResolveResult,
} from "../mediaSource";
import { sortedSegments, type SkipKind, type SkipSegment } from "../skipSegment";
import type { PlexClient, PlexServer, PlexSession } from "./client";
import { isTextSubtitleCodec, plexPartId, type ExternalSubtitle, type StoredSeekFrames } from "../seekThumbs";
import type { PlexContainer, PlexMedia, PlexMetadata, PlexPart, PlexResponse } from "./dtos";

export const PLEX_KIND = "plex";

/** The [MediaSource.key] of [session]'s server; the same server reached at another address keeps it. */
export const plexKeyOf = (session: Pick<PlexSession, "machineId" | "serverUrl">): string => `${PLEX_KIND}:${session.machineId || session.serverUrl}`;

/** The [MediaSource.key] [server] gets once it's added. */
export const plexServerKeyOf = (server: Pick<PlexServer, "machineId">): string => `${PLEX_KIND}:${server.machineId}`;

const SECTION_PREFIX = "section:";
const TYPE_MOVIE = "1";
const TYPE_EPISODE = "4";
const PAGE_SIZE = 1000;
const MIN_REFRESH_MS = 30_000;
const VIDEO_SECTIONS = new Set(["movie", "show"]);
const SEARCH_TYPES = new Set(["movie", "show", "episode", "clip"]);
const RECENT_TYPES = new Set(["movie", "episode", "season", "clip"]);

const has = (set: Set<string>, value: string | null | undefined) => value != null && set.has(value);

/**
 * One Plex server (PlexSource.kt): libraries, search, playable streams that answer HTTP Range requests (the part's
 * own URL with the token), playback reports, intro and credits markers, and finding a shared playlist's file by name.
 */
export class PlexSource implements MediaSource {
  readonly key: string;
  readonly kind = PLEX_KIND;
  private readonly index: FilenameIndex;
  private readonly partByItem = new Map<string, string>();
  private readonly subsByItem = new Map<string, ExternalSubtitle[]>();

  constructor(
    private readonly client: PlexClient,
    readonly session: PlexSession,
    clock: () => number = Date.now,
  ) {
    this.key = plexKeyOf(session);
    this.index = new FilenameIndex(clock, MIN_REFRESH_MS);
  }

  get displayName(): string {
    return this.session.serverName;
  }

  private get base() {
    return this.session.serverUrl;
  }

  private get token() {
    return this.session.serverToken;
  }

  private async container(path: string, query: Query = []): Promise<PlexContainer> {
    return (await this.client.get<PlexResponse>(this.base, this.token, path, query))?.MediaContainer ?? {};
  }

  async libraries(): Promise<MediaItem[]> {
    const sections = (await this.container("library/sections")).Directory ?? [];
    return sections
      .filter((d) => has(VIDEO_SECTIONS, d.type))
      .map((d) =>
        mediaItem({
          id: SECTION_PREFIX + (d.key ?? ""),
          name: d.title ?? "",
          type: "LIBRARY",
          isFolder: true,
          collectionType: d.type === "show" ? "tvshows" : "movies",
          imageTag: d.art ?? d.thumb ?? null,
          sourceKey: this.key,
        }),
      );
  }

  async children(parent: MediaItem): Promise<MediaItem[]> {
    const path = parent.id.startsWith(SECTION_PREFIX)
      ? `library/sections/${parent.id.slice(SECTION_PREFIX.length)}/all`
      : `library/metadata/${parent.id}/children`;
    return ((await this.container(path)).Metadata ?? []).map((m) => this.toMediaItem(m));
  }

  async search(query: string, limit = 60): Promise<MediaItem[]> {
    if (query.trim() === "") return [];
    const hits = await this.searchHits(query.trim(), SEARCH_TYPES, limit);
    return hits.slice(0, limit).map((m) => this.toMediaItem(m));
  }

  async recent(limit = 24): Promise<MediaItem[]> {
    const onDeck = await this.container("library/onDeck")
      .then((c) => c.Metadata ?? [])
      .catch(() => []);
    const added =
      (
        await this.container("library/recentlyAdded", [
          ["X-Plex-Container-Start", "0"],
          ["X-Plex-Container-Size", String(limit)],
        ])
      ).Metadata ?? [];
    const seen = new Set<string>();
    return [...onDeck, ...added]
      .filter((m) => has(RECENT_TYPES, m.type))
      .filter((m) => (seen.has(m.ratingKey) ? false : (seen.add(m.ratingKey), true)))
      .slice(0, limit)
      .map((m) => this.toMediaItem(m));
  }

  async playable(item: MediaItem): Promise<PlayableMedia> {
    const meta = await this.metadata(item.id);
    if (!meta) throw new MediaSourceError(`Item ${item.name} no longer exists`);
    return this.playableFrom(meta, fileNameOf(item));
  }

  private async metadata(ratingKey: string): Promise<PlexMetadata | null> {
    return (await this.container(`library/metadata/${ratingKey}`)).Metadata?.[0] ?? null;
  }

  private rememberPart(meta: PlexMetadata, part: PlexPart) {
    const id = part.key ? plexPartId(part.key) : null;
    if (id) this.partByItem.set(meta.ratingKey, id);
    const subs: ExternalSubtitle[] = [];
    for (const stream of part.Stream ?? []) {
      const key = stream.key;
      if (!key?.startsWith("/library/streams/") || stream.streamType !== 3 || !isTextSubtitleCodec(stream.codec)) continue;
      subs.push({
        url: this.client.url(this.base, key, [["X-Plex-Token", this.token]]).href,
        title: stream.displayTitle?.trim() || "Subtitles",
        language: stream.languageCode ?? stream.language ?? null,
      });
    }
    this.subsByItem.set(meta.ratingKey, subs);
  }

  async storedSeekFrames(itemId: string): Promise<StoredSeekFrames | null> {
    let part = this.partByItem.get(itemId);
    if (!part) {
      const meta = await this.metadata(itemId);
      const chosen = meta?.Media?.flatMap((m) => m.Part ?? []).find((p) => p.key);
      if (!meta || !chosen) {
        if (!this.subsByItem.has(itemId)) this.subsByItem.set(itemId, []);
        return null;
      }
      this.rememberPart(meta, chosen);
      part = this.partByItem.get(itemId);
      if (!part) return null;
    }
    let index: string | null = null;
    try {
      const body = await this.client.get<{ MediaContainer?: { Index?: { id?: string | number } | { id?: string | number }[] } }>(
        this.base,
        this.token,
        `library/parts/${part}/indexes`,
      );
      const raw = body.MediaContainer?.Index;
      const first = Array.isArray(raw) ? raw[0] : raw;
      index = first?.id != null && String(first.id) !== "" ? String(first.id) : null;
    } catch {
      index = null;
    }
    if (!index) return null;
    return { kind: "plex", server: this.base, partId: part, indexId: index, token: this.token };
  }

  async externalSubtitles(itemId: string): Promise<ExternalSubtitle[]> {
    if (!this.subsByItem.has(itemId)) await this.storedSeekFrames(itemId);
    return this.subsByItem.get(itemId) ?? [];
  }

  async skipSegments(itemId: string): Promise<SkipSegment[]> {
    const meta = (await this.container(`library/metadata/${itemId}`, [["includeMarkers", "1"]])).Metadata?.[0];
    if (!meta) return [];
    const segments: SkipSegment[] = [];
    for (const m of meta.Marker ?? []) {
      const kind: SkipKind | null = m.type === "intro" ? "OPENING" : m.type === "credits" ? "ENDING" : null;
      if (kind === null) continue;
      segments.push({ kind, start: (m.startTimeOffset ?? 0) / 1000, end: (m.endTimeOffset ?? 0) / 1000 });
    }
    return sortedSegments(segments);
  }

  async seasonEpisodes(itemId: string): Promise<PlayableMedia[]> {
    const meta = await this.metadata(itemId);
    const season = meta?.type === "episode" ? meta.parentRatingKey : null;
    if (!season) return [];
    const episodes = (await this.container(`library/metadata/${season}/children`)).Metadata ?? [];
    return episodes
      .filter((m) => m.type === "episode")
      .flatMap((m) => {
        try {
          return [this.playableFrom(m)];
        } catch {
          return [];
        }
      });
  }

  /** Uses the part named [preferredFile] when the item has several (multi-part or multi-version). */
  private playableFrom(meta: PlexMetadata, preferredFile: string | null = null): PlayableMedia {
    const parts: [PlexMedia, PlexPart & { key: string; file: string }][] = [];
    for (const media of meta.Media ?? []) {
      for (const part of media.Part ?? []) {
        if (part.key != null && part.file != null) parts.push([media, { ...part, key: part.key, file: part.file }]);
      }
    }
    const want = preferredFile?.toLowerCase();
    const chosen = (want !== undefined ? parts.find(([, p]) => FileNames.baseName(p.file).toLowerCase() === want) : undefined) ?? parts[0];
    if (!chosen) throw new MediaSourceError(`${meta.title} has no file`);
    const [media, part] = chosen;
    const millis = part.duration ?? media.duration ?? meta.duration ?? 0;
    this.rememberPart(meta, part);
    const item = this.toMediaItem(meta);
    const series = meta.type === "episode" && item.seriesName !== null && item.seriesName.trim() !== "" ? item.seriesName : null;
    return {
      itemId: meta.ratingKey,
      url: this.client.url(this.base, part.key, [["X-Plex-Token", this.token]]).href,
      fileName: FileNames.baseName(part.file),
      sizeBytes: part.size ?? 0,
      durationSeconds: millis / 1000,
      title: item.seriesName !== null ? `${item.seriesName} · ${displayTitle(item)}` : displayTitle(item),
      sourceKey: this.key,
      episode:
        series === null
          ? null
          : {
              seriesName: series,
              season: item.parentIndexNumber,
              episode: item.indexNumber,
              year: meta.year ?? null,
              seriesKey: `${this.key}/${meta.grandparentRatingKey ?? series}`,
              malId: null,
            },
    };
  }

  imageUrl(item: MediaItem, maxWidth = 400): string | null {
    if (item.imageTag === null) return null;
    return this.client.url(this.base, "photo/:/transcode", [
      ["width", String(maxWidth)],
      ["height", String(maxWidth * 2)],
      ["url", item.imageTag],
      ["X-Plex-Token", this.token],
    ]).href;
  }

  async reportPlayback(report: PlaybackReport): Promise<void> {
    const state = report.state === "PAUSED" ? "paused" : report.state === "STOPPED" ? "stopped" : "playing";
    await this.client.getRaw(this.base, this.token, ":/timeline", { "X-Plex-Session-Identifier": report.sessionId }, [
      ["ratingKey", report.itemId],
      ["key", `/library/metadata/${report.itemId}`],
      ["state", state],
      ["time", String(Math.trunc(report.positionSeconds * 1000))],
      ["duration", String(Math.trunc(report.durationSeconds * 1000))],
    ]);
    if (report.markWatched) {
      await this.client.getRaw(this.base, this.token, ":/scrobble", {}, [
        ["key", report.itemId],
        ["identifier", "com.plexapp.plugins.library"],
      ]);
    }
  }

  // --- Filename resolution (shared playlist auto-load) ------------------------------------

  /** Builds (or refreshes) the basename index of every movie and episode file on the server. */
  refreshIndex(force = false): Promise<void> {
    return this.index.refresh(force, async () => {
      const entries: IndexEntry[] = [];
      const sections = ((await this.container("library/sections")).Directory ?? []).filter((d) => has(VIDEO_SECTIONS, d.type));
      for (const section of sections) {
        const type = section.type === "show" ? TYPE_EPISODE : TYPE_MOVIE;
        let start = 0;
        for (;;) {
          const page = await this.container(`library/sections/${section.key}/all`, [
            ["type", type],
            ["X-Plex-Container-Start", String(start)],
            ["X-Plex-Container-Size", String(PAGE_SIZE)],
          ]);
          const metadata = page.Metadata ?? [];
          for (const meta of metadata) {
            for (const media of meta.Media ?? []) {
              for (const part of media.Part ?? []) {
                if (part.file != null) entries.push({ id: meta.ratingKey, fileName: FileNames.baseName(part.file) });
              }
            }
          }
          start += metadata.length;
          if (metadata.length === 0 || start >= (page.totalSize ?? start)) break;
        }
      }
      return entries;
    });
  }

  get indexedFileCount(): number {
    return this.index.size;
  }

  async findExact(fileName: string): Promise<Found | null> {
    const name = FileNames.baseName(fileName.trim());
    if (name === "") return null;
    if (!this.index.isBuilt) await this.refreshIndex().catch(() => {});
    let candidates = this.index.exact(name);
    if (candidates.length === 0) {
      await this.refreshIndex().catch(() => {});
      candidates = this.index.exact(name);
    }
    return this.foundIn(candidates, "EXACT_FILENAME");
  }

  async resolveByFilename(fileName: string): Promise<ResolveResult> {
    const name = FileNames.baseName(fileName.trim());
    if (name === "") return notFound(fileName, "Empty file name");

    const first = await this.lookupIndex(name);
    if (first) return first;
    await this.refreshIndex().catch(() => {});
    const second = await this.lookupIndex(name);
    if (second) return second;

    try {
      return (await this.searchParsed(name)) ?? notFound(name, `Not found in ${this.session.serverName}`);
    } catch (e) {
      return notFound(name, messageOf(e) || "Lookup failed");
    }
  }

  private async lookupIndex(name: string): Promise<Found | null> {
    if (!this.index.isBuilt) await this.refreshIndex().catch(() => {});
    const hit = this.index.lookup(name);
    return hit ? this.foundIn(hit.entries, hit.kind) : null;
  }

  private async foundIn(candidates: IndexEntry[], kind: MatchKind): Promise<Found | null> {
    for (const candidate of candidates) {
      const meta = await this.metadata(candidate.id).catch(() => null);
      if (!meta) continue;
      let playable: PlayableMedia;
      try {
        playable = this.playableFrom(meta, candidate.fileName);
      } catch {
        continue;
      }
      return found(this.toMediaItem(meta), playable, kind);
    }
    return null;
  }

  private async searchHits(title: string, types: Set<string>, limit: number): Promise<PlexMetadata[]> {
    const hubs =
      (
        await this.container("hubs/search", [
          ["query", title],
          ["limit", String(limit)],
        ])
      ).Hub ?? [];
    return hubs.flatMap((h) => h.Metadata ?? []).filter((m) => has(types, m.type));
  }

  private async searchParsed(name: string): Promise<Found | null> {
    const parsed = FileNames.parse(name);
    if (parsed.title.trim() === "") return null;
    if (parsed.episode !== null) {
      for (const show of await this.searchHits(parsed.title, new Set(["show"]), 5)) {
        const episodes = (await this.container(`library/metadata/${show.ratingKey}/allLeaves`)).Metadata ?? [];
        const match = episodes.find((e) => e.index === parsed.episode && (parsed.season === null || e.parentIndex === parsed.season));
        if (!match) continue;
        return found(this.toMediaItem(match), this.playableFrom(match), "PARSED_SEARCH");
      }
      return null;
    }
    const movies = await this.searchHits(parsed.title, new Set(["movie", "clip"]), 10);
    const match = movies.find((m) => parsed.year !== null && m.year === parsed.year) ?? (movies.length === 1 ? movies[0] : undefined);
    if (!match) return null;
    const full = (match.Media ?? []).length === 0 ? await this.metadata(match.ratingKey) : match;
    if (!full) return null;
    return found(this.toMediaItem(full), this.playableFrom(full), "PARSED_SEARCH");
  }

  private toMediaItem(meta: PlexMetadata): MediaItem {
    const t: MediaItemType =
      meta.type === "movie"
        ? "MOVIE"
        : meta.type === "show"
          ? "SERIES"
          : meta.type === "season"
            ? "SEASON"
            : meta.type === "episode"
              ? "EPISODE"
              : meta.type === "clip"
                ? "VIDEO"
                : "OTHER";
    const folder = t === "SERIES" || t === "SEASON";
    const leafCount = meta.leafCount ?? null;
    return mediaItem({
      id: meta.ratingKey,
      name: meta.title ?? "",
      type: t,
      isFolder: folder,
      overview: meta.summary ?? null,
      year: meta.year ?? null,
      indexNumber: meta.index ?? null,
      parentIndexNumber: t === "EPISODE" ? (meta.parentIndex ?? null) : null,
      seriesName: t === "EPISODE" ? (meta.grandparentTitle ?? null) : t === "SEASON" ? (meta.parentTitle ?? null) : t === "SERIES" ? (meta.title ?? null) : null,
      seriesId:
        t === "EPISODE" ? (meta.grandparentRatingKey ?? null) : t === "SEASON" ? (meta.parentRatingKey ?? null) : t === "SERIES" ? meta.ratingKey : null,
      durationSeconds: meta.duration != null ? meta.duration / 1000 : null,
      path: meta.Media?.[0]?.Part?.[0]?.file ?? null,
      imageTag: meta.thumb ?? null,
      played: folder ? leafCount !== null && leafCount > 0 && leafCount === meta.viewedLeafCount : (meta.viewCount ?? 0) > 0,
      resumeSeconds: (meta.viewOffset ?? 0) / 1000,
      sourceKey: this.key,
    });
  }
}
