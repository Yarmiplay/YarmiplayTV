import * as FileNames from "../fileNames";
import { FilenameIndex, type IndexEntry } from "../filenameIndex";
import type { Query } from "../http";
import {
  displayTitle,
  found,
  MediaSourceError,
  mediaItem,
  messageOf,
  notFound,
  type EpisodeInfo,
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
import type { JellyfinClient, JellyfinSession } from "./client";
import type { BaseItemDto, ItemsResult, MediaSegmentsResult, PlaybackProgressBody } from "./dtos";

export const JELLYFIN_KIND = "jellyfin";

/** The [MediaSource.key] of [session]'s server; signing in to the same server again gives the same key. */
export const jellyfinKeyOf = (session: Pick<JellyfinSession, "serverId" | "serverUrl">): string =>
  `${JELLYFIN_KIND}:${session.serverId || session.serverUrl}`;

const TICKS_PER_SECOND = 10_000_000;
const PAGE_SIZE = 2000;
const MIN_REFRESH_MS = 30_000;
const LIST_FIELDS = "Overview,Path,ProductionYear,ProviderIds";
const VIDEO_COLLECTIONS = new Set(["movies", "tvshows", "homevideos", "musicvideos", "mixed", "folders", "boxsets"]);
const FOLDER_TYPES: ReadonlySet<MediaItemType> = new Set(["LIBRARY", "FOLDER", "SERIES", "SEASON"]);

/** Kotlin's toIntOrNull: a whole number, or null. */
const toIntOrNull = (s: string | undefined): number | null => (s !== undefined && /^[+-]?\d+$/.test(s) ? Number(s) : null);

/**
 * One Jellyfin server, browsed as the signed-in user (JellyfinSource.kt): libraries, search, playable streams that
 * answer HTTP Range requests (`Videos/{id}/stream?static=true`), playback reports, skip markers, and finding a shared
 * playlist's file by name.
 */
export class JellyfinSource implements MediaSource {
  readonly key: string;
  readonly kind = JELLYFIN_KIND;
  private readonly index: FilenameIndex;
  /** The media source (version) each item was last resolved to, so reports name the one being played. */
  private readonly mediaSourceIds = new Map<string, string>();

  constructor(
    private readonly client: JellyfinClient,
    readonly session: JellyfinSession,
    clock: () => number = Date.now,
  ) {
    this.key = jellyfinKeyOf(session);
    this.index = new FilenameIndex(clock, MIN_REFRESH_MS);
  }

  get displayName(): string {
    return this.session.serverName;
  }

  private get base() {
    return this.session.serverUrl;
  }

  private get token() {
    return this.session.accessToken;
  }

  private get<T>(path: string, query: Query = []): Promise<T> {
    return this.client.get<T>(this.base, this.token, path, query);
  }

  private librariesCache: { timestamp: number; items: MediaItem[] } | null = null;

  private async items(query: Query): Promise<BaseItemDto[]> {
    return (await this.get<ItemsResult>("Items", [["userId", this.session.userId], ["enableTotalRecordCount", "false"], ...query])).Items ?? [];
  }

  async libraries(): Promise<MediaItem[]> {
    const now = Date.now();
    if (this.librariesCache && now - this.librariesCache.timestamp < 60_000) {
      return this.librariesCache.items;
    }
    let views: ItemsResult;
    try {
      views = await this.get("UserViews", [["userId", this.session.userId]]);
    } catch (e) {
      if (!(e instanceof MediaSourceError) || e.httpCode !== 404) throw e;
      views = await this.get(`Users/${this.session.userId}/Views`);
    }
    const items = (views.Items ?? [])
      .filter((v) => v.CollectionType == null || VIDEO_COLLECTIONS.has(v.CollectionType))
      .map((v) => this.toMediaItem(v, "LIBRARY"));
    this.librariesCache = { timestamp: now, items };
    return items;
  }

  async children(parent: MediaItem): Promise<MediaItem[]> {
    let dtos: BaseItemDto[];
    if (parent.type === "SEASON" && parent.seriesId !== null) {
      const episodes = await this.get<ItemsResult>(`Shows/${parent.seriesId}/Episodes`, [
        ["userId", this.session.userId],
        ["seasonId", parent.id],
        ["fields", LIST_FIELDS],
      ]);
      dtos = episodes.Items ?? [];
    } else if (parent.type === "LIBRARY" && (parent.collectionType === "tvshows" || parent.collectionType === "movies")) {
      dtos = await this.items([
        ["parentId", parent.id],
        ["recursive", "true"],
        ["includeItemTypes", parent.collectionType === "tvshows" ? "Series" : "Movie"],
        ["sortBy", "SortName"],
        ["fields", LIST_FIELDS],
      ]);
    } else {
      dtos = await this.items([
        ["parentId", parent.id],
        ["sortBy", parent.type === "SERIES" ? "SortName" : "IsFolder,SortName"],
        ["fields", LIST_FIELDS],
      ]);
    }
    return dtos.map((d) => this.toMediaItem(d));
  }

  async search(query: string, limit = 60): Promise<MediaItem[]> {
    if (query.trim() === "") return [];
    const hits = await this.items([
      ["searchTerm", query.trim()],
      ["recursive", "true"],
      ["includeItemTypes", "Series,Movie,Episode,Video"],
      ["limit", String(limit)],
      ["fields", LIST_FIELDS],
      ["imageTypeLimit", "1"],
    ]);
    return hits.map((d) => this.toMediaItem(d));
  }

  async recent(limit = 24): Promise<MediaItem[]> {
    let resume: BaseItemDto[];
    try {
      const r = await this.get<ItemsResult>("UserItems/Resume", [
        ["userId", this.session.userId],
        ["limit", String(limit)],
        ["mediaTypes", "Video"],
        ["fields", LIST_FIELDS],
      ]);
      resume = r.Items ?? [];
    } catch {
      try {
        const r = await this.get<ItemsResult>(`Users/${this.session.userId}/Items/Resume`, [
          ["limit", String(limit)],
          ["fields", LIST_FIELDS],
        ]);
        resume = r.Items ?? [];
      } catch {
        resume = [];
      }
    }
    const latest = await this.items([
      ["recursive", "true"],
      ["includeItemTypes", "Episode,Movie"],
      ["sortBy", "DateCreated"],
      ["sortOrder", "Descending"],
      ["limit", String(limit)],
      ["fields", LIST_FIELDS],
    ]);
    const seen = new Set<string>();
    return [...resume, ...latest]
      .filter((d) => (seen.has(d.Id) ? false : (seen.add(d.Id), true)))
      .slice(0, limit)
      .map((d) => this.toMediaItem(d));
  }

  async playable(item: MediaItem): Promise<PlayableMedia> {
    const [dto] = await this.items([
      ["ids", item.id],
      ["fields", "Path,MediaSources"],
    ]);
    if (!dto) throw new MediaSourceError(`Item ${item.name} no longer exists`);
    return this.playableFrom(dto);
  }

  private playableFrom(dto: BaseItemDto): PlayableMedia {
    const sources = dto.MediaSources ?? [];
    const source = sources.find((s) => s.Protocol == null || s.Protocol === "File") ?? sources[0];
    const path = source?.Path ?? dto.Path;
    if (!path) throw new MediaSourceError(`${dto.Name} has no file`);
    const ticks = source?.RunTimeTicks ?? dto.RunTimeTicks ?? 0;
    if (source?.Id) this.mediaSourceIds.set(dto.Id, source.Id);
    const m = this.toMediaItem(dto);
    return {
      itemId: dto.Id,
      url: this.streamUrl(dto.Id, source?.Id ?? null),
      fileName: FileNames.baseName(path),
      sizeBytes: source?.Size ?? 0,
      durationSeconds: ticks / TICKS_PER_SECOND,
      title: m.seriesName !== null ? `${m.seriesName} · ${displayTitle(m)}` : displayTitle(m),
      sourceKey: this.key,
      episode: this.episodeInfo(dto),
    };
  }

  /** An episode's show, season and number as Jellyfin has them; a MyAnimeList id when an anime plugin set one. */
  private episodeInfo(dto: BaseItemDto): EpisodeInfo | null {
    if (dto.Type !== "Episode") return null;
    const series = dto.SeriesName;
    if (!series || series.trim() === "") return null;
    const malEntry = Object.entries(dto.ProviderIds ?? {}).find(([k]) => k.toLowerCase() === "myanimelist" || k.toLowerCase() === "mal");
    return {
      seriesName: series,
      season: dto.ParentIndexNumber ?? null,
      episode: dto.IndexNumber ?? null,
      year: dto.ProductionYear ?? null,
      seriesKey: `${this.key}/${dto.SeriesId ?? series}`,
      malId: toIntOrNull(malEntry?.[1]),
    };
  }

  async reportPlayback(report: PlaybackReport): Promise<void> {
    const path =
      report.state === "STARTED" ? "Sessions/Playing" : report.state === "STOPPED" ? "Sessions/Playing/Stopped" : "Sessions/Playing/Progress";
    const mediaSourceId = this.mediaSourceIds.get(report.itemId);
    const body: PlaybackProgressBody = {
      ItemId: report.itemId,
      ...(mediaSourceId !== undefined ? { MediaSourceId: mediaSourceId } : {}),
      PlaySessionId: report.sessionId,
      PositionTicks: Math.trunc(report.positionSeconds * TICKS_PER_SECOND),
      IsPaused: report.state === "PAUSED",
      CanSeek: true,
      PlayMethod: "DirectPlay",
    };
    await this.client.postRaw(this.base, this.token, path, JSON.stringify(body));
    if (report.markWatched) await this.markPlayed(report.itemId);
  }

  async skipSegments(itemId: string): Promise<SkipSegment[]> {
    let result: MediaSegmentsResult;
    try {
      result = await this.get(`MediaSegments/${itemId}`, [
        ["includeSegmentTypes", "Intro"],
        ["includeSegmentTypes", "Outro"],
      ]);
    } catch (e) {
      // Before 10.10 there are no media segments.
      if (e instanceof MediaSourceError && e.httpCode === 404) return [];
      throw e;
    }
    const segments: SkipSegment[] = [];
    for (const s of result.Items ?? []) {
      const kind: SkipKind | null = s.Type === "Intro" ? "OPENING" : s.Type === "Outro" ? "ENDING" : null;
      if (kind === null) continue;
      segments.push({ kind, start: (s.StartTicks ?? 0) / TICKS_PER_SECOND, end: (s.EndTicks ?? 0) / TICKS_PER_SECOND });
    }
    return sortedSegments(segments);
  }

  async seasonEpisodes(itemId: string): Promise<PlayableMedia[]> {
    const [episode] = await this.items([["ids", itemId]]);
    const series = episode?.SeriesId;
    const season = episode?.SeasonId;
    if (!series || !season) return [];
    const episodes = await this.get<ItemsResult>(`Shows/${series}/Episodes`, [
      ["userId", this.session.userId],
      ["seasonId", season],
      ["fields", "Path,MediaSources"],
    ]);
    return (episodes.Items ?? []).flatMap((d) => {
      try {
        return [this.playableFrom(d)];
      } catch {
        return [];
      }
    });
  }

  private async markPlayed(itemId: string): Promise<void> {
    try {
      await this.client.postRaw(this.base, this.token, `UserPlayedItems/${itemId}`, null, [["userId", this.session.userId]]);
    } catch (e) {
      if (!(e instanceof MediaSourceError) || e.httpCode !== 404) throw e;
      await this.client.postRaw(this.base, this.token, `Users/${this.session.userId}/PlayedItems/${itemId}`, null);
    }
  }

  /** The item's file as it is on the server (no transcoding), with the token in the query so plain Range reads work. */
  streamUrl(itemId: string, mediaSourceId: string | null): string {
    const query: Query = [["static", "true"]];
    if (mediaSourceId !== null) query.push(["mediaSourceId", mediaSourceId]);
    query.push(["api_key", this.token]);
    return this.client.url(this.base, `Videos/${itemId}/stream`, query).href;
  }

  imageUrl(item: MediaItem, maxWidth = 400): string | null {
    if (item.imageTag === null) return null;
    return this.client.url(this.base, `Items/${item.id}/Images/Primary`, [
      ["maxWidth", String(maxWidth)],
      ["quality", "90"],
      ["tag", item.imageTag],
    ]).href;
  }

  // --- Filename resolution (shared playlist auto-load) ------------------------------------

  /** Builds (or refreshes) the basename index of every video file across the user's libraries. */
  refreshIndex(force = false): Promise<void> {
    return this.index.refresh(force, async () => {
      const entries: IndexEntry[] = [];
      let start = 0;
      for (;;) {
        const page = await this.get<ItemsResult>("Items", [
          ["userId", this.session.userId],
          ["recursive", "true"],
          ["includeItemTypes", "Movie,Episode,Video,MusicVideo"],
          ["fields", "Path"],
          ["enableImages", "false"],
          ["enableUserData", "false"],
          ["startIndex", String(start)],
          ["limit", String(PAGE_SIZE)],
        ]);
        const items = page.Items ?? [];
        for (const dto of items) if (dto.Path) entries.push({ id: dto.Id, fileName: FileNames.baseName(dto.Path) });
        start += items.length;
        if (items.length === 0 || start >= (page.TotalRecordCount ?? 0)) break;
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
    // A miss may just mean the index is stale (new file added since we built it).
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
      let dto: BaseItemDto | undefined;
      try {
        [dto] = await this.items([
          ["ids", candidate.id],
          ["fields", `Path,MediaSources,${LIST_FIELDS}`],
        ]);
      } catch {
        continue;
      }
      if (!dto) continue;
      let playable: PlayableMedia;
      try {
        playable = this.playableFrom(dto);
      } catch {
        continue;
      }
      return found(this.toMediaItem(dto), playable, kind);
    }
    return null;
  }

  private async searchParsed(name: string): Promise<Found | null> {
    const parsed = FileNames.parse(name);
    if (parsed.title.trim() === "") return null;
    const fields = `Path,MediaSources,${LIST_FIELDS}`;
    if (parsed.episode !== null) {
      const series = await this.items([
        ["searchTerm", parsed.title],
        ["recursive", "true"],
        ["includeItemTypes", "Series"],
        ["limit", "5"],
      ]);
      for (const s of series) {
        const episodes = await this.items([
          ["parentId", s.Id],
          ["recursive", "true"],
          ["includeItemTypes", "Episode"],
          ["fields", fields],
        ]);
        const match =
          episodes.find((e) => e.IndexNumber === parsed.episode && (parsed.season === null || e.ParentIndexNumber === parsed.season)) ??
          episodes.find((e) => e.IndexNumber === parsed.episode && parsed.season === 1 && e.ParentIndexNumber == null);
        if (match) return found(this.toMediaItem(match), this.playableFrom(match), "PARSED_SEARCH");
      }
      return null;
    }
    const movies = await this.items([
      ["searchTerm", parsed.title],
      ["recursive", "true"],
      ["includeItemTypes", "Movie,Video"],
      ["limit", "10"],
      ["fields", fields],
    ]);
    const match = movies.find((m) => parsed.year !== null && m.ProductionYear === parsed.year) ?? (movies.length === 1 ? movies[0] : undefined);
    return match ? found(this.toMediaItem(match), this.playableFrom(match), "PARSED_SEARCH") : null;
  }

  private toMediaItem(dto: BaseItemDto, forceType?: MediaItemType): MediaItem {
    const t: MediaItemType = forceType ?? typeOf(dto);
    return mediaItem({
      id: dto.Id,
      name: dto.Name ?? "",
      type: t,
      isFolder: dto.IsFolder ?? FOLDER_TYPES.has(t),
      collectionType: dto.CollectionType ?? null,
      overview: dto.Overview ?? null,
      year: dto.ProductionYear ?? null,
      indexNumber: dto.IndexNumber ?? null,
      parentIndexNumber: dto.ParentIndexNumber ?? null,
      seriesName: dto.SeriesName ?? null,
      seriesId: dto.SeriesId ?? (t === "SERIES" ? dto.Id : null),
      durationSeconds: dto.RunTimeTicks != null ? dto.RunTimeTicks / TICKS_PER_SECOND : null,
      path: dto.Path ?? dto.MediaSources?.[0]?.Path ?? null,
      imageTag: dto.ImageTags?.["Primary"] ?? null,
      played: dto.UserData?.Played ?? false,
      resumeSeconds: (dto.UserData?.PlaybackPositionTicks ?? 0) / TICKS_PER_SECOND,
      sourceKey: this.key,
    });
  }
}

function typeOf(dto: BaseItemDto): MediaItemType {
  switch (dto.Type) {
    case "CollectionFolder":
    case "UserView":
      return "LIBRARY";
    case "Series":
      return "SERIES";
    case "Season":
      return "SEASON";
    case "Episode":
      return "EPISODE";
    case "Movie":
      return "MOVIE";
    case "Video":
    case "MusicVideo":
      return "VIDEO";
    case "Folder":
    case "BoxSet":
      return "FOLDER";
    default:
      return dto.IsFolder === true ? "FOLDER" : "OTHER";
  }
}
