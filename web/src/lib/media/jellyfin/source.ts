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
import {
  bothSchemesFailed,
  plainHttpUrl,
  preferredProbeUrl,
  type JellyfinClient,
  type JellyfinSession,
} from "./client";
import {
  isTextSubtitleCodec,
  trickplayWidth,
  type ExternalSubtitle,
  type StoredSeekFrames,
  type TrickplaySet,
} from "../seekThumbs";
import type { BaseItemDto, ItemsResult, MediaSegmentsResult, MediaSourceInfo, PlaybackProgressBody } from "./dtos";

export const JELLYFIN_KIND = "jellyfin";

/**
 * Identity of a saved Jellyfin entry: its base URL (not ServerId).
 * The same Jellyfin instance at a LAN IP and a public URL are two entries so both stay saved.
 * Signing in again at the same address replaces that entry.
 */
export const jellyfinKeyOf = (session: Pick<JellyfinSession, "serverId" | "serverUrl">): string => {
  const url = session.serverUrl.trim().replace(/\/+$/, "");
  return `${JELLYFIN_KIND}:${url || session.serverId}`;
};

/** Pre-2.8.7 key for a session (`jellyfin:<ServerId>`), or null when ServerId is empty. */
export const jellyfinLegacyKeyOf = (session: Pick<JellyfinSession, "serverId">): string | null => {
  const id = session.serverId.trim();
  return id ? `${JELLYFIN_KIND}:${id}` : null;
};

/**
 * Rewrites a preferred-server / MAL key that used `jellyfin:<ServerId>` to `jellyfin:<url>`
 * for [servers]. Leaves the key unchanged when it doesn't match a legacy id.
 */
export const jellyfinMigrateStoredKey = (
  key: string,
  servers: Array<Pick<JellyfinSession, "serverId" | "serverUrl">>,
): string => {
  if (!key || !key.startsWith(`${JELLYFIN_KIND}:`)) return key;
  for (const session of servers) {
    const legacy = jellyfinLegacyKeyOf(session);
    if (!legacy) continue;
    const modern = jellyfinKeyOf(session);
    if (legacy === modern) continue;
    if (key === legacy || key.startsWith(`${legacy}/`) || key.startsWith(`${legacy}|`)) {
      return modern + key.slice(legacy.length);
    }
  }
  for (const session of servers) {
    const modern = jellyfinKeyOf(session);
    const http = plainHttpUrl(session.serverUrl);
    if (!http) continue;
    const oldKey = `${JELLYFIN_KIND}:${http}`;
    if (oldKey === modern) continue;
    if (key === oldKey || key.startsWith(`${oldKey}/`) || key.startsWith(`${oldKey}|`)) {
      return modern + key.slice(oldKey.length);
    }
  }
  return key;
};

const TICKS_PER_SECOND = 10_000_000;
const PAGE_SIZE = 2000;
const MIN_REFRESH_MS = 30_000;
const LIST_FIELDS = "Overview,Path,ProductionYear,ProviderIds,Album,AlbumId,AlbumArtist,Artists";
const VIDEO_COLLECTIONS = new Set(["movies", "tvshows", "homevideos", "musicvideos", "mixed", "folders", "boxsets", "music"]);
const FOLDER_TYPES: ReadonlySet<MediaItemType> = new Set(["LIBRARY", "FOLDER", "SERIES", "SEASON", "ARTIST", "ALBUM"]);

/** Kotlin's toIntOrNull: a whole number, or null. */
const toIntOrNull = (s: string | undefined): number | null => (s !== undefined && /^[+-]?\d+$/.test(s) ? Number(s) : null);

/**
 * One Jellyfin server, browsed as the signed-in user (JellyfinSource.kt): libraries, search, playable streams that
 * answer HTTP Range requests (`Videos/{id}/stream?static=true` or `Audio/{id}/stream?static=true`), playback reports,
 * skip markers, and finding a shared playlist's file by name.
 */
export class JellyfinSource implements MediaSource {
  readonly kind = JELLYFIN_KIND;
  private readonly index: FilenameIndex;
  /** The media source (version) each item was last resolved to, so reports name the one being played. */
  private readonly mediaSourceIds = new Map<string, string>();
  private readonly subsByItem = new Map<string, ExternalSubtitle[]>();
  /** Request base for this page load; may be http after a session-only SSL fallback. */
  private requestBase: string;

  private rememberStreams(itemId: string, source: MediaSourceInfo | undefined) {
    const subs: ExternalSubtitle[] = [];
    for (const stream of source?.MediaStreams ?? []) {
      if (stream.Type !== "Subtitle" || stream.IsExternal !== true || !isTextSubtitleCodec(stream.Codec)) continue;
      const delivery = stream.DeliveryUrl?.trim();
      if (!delivery) continue;
      subs.push({
        url: this.withToken(delivery),
        title: stream.DisplayTitle?.trim() || "Subtitles",
        language: stream.Language?.trim() || null,
      });
    }
    this.subsByItem.set(itemId, subs);
  }

  private withToken(delivery: string): string {
    const absolute = delivery.startsWith("http://") || delivery.startsWith("https://") ? delivery : `${this.base.replace(/\/+$/, "")}/${delivery.replace(/^\/+/, "")}`;
    if (absolute.includes("api_key=")) return absolute;
    return `${absolute}${absolute.includes("?") ? "&" : "?"}api_key=${encodeURIComponent(this.token)}`;
  }

  async storedSeekFrames(itemId: string): Promise<StoredSeekFrames | null> {
    const dto = (await this.items([["ids", itemId], ["fields", "Path,MediaSources"]]))[0];
    if (!dto) return null;
    const sources = dto.MediaSources ?? [];
    const source = sources.find((s) => s.Protocol == null || s.Protocol === "File") ?? sources[0];
    this.rememberStreams(itemId, source);
    const sets = source?.Trickplay ?? {};
    const width = trickplayWidth(Object.keys(sets).map((k) => Number(k)).filter((n) => Number.isFinite(n)));
    const info = width === null ? null : sets[String(width)];
    if (width === null || !info) return null;
    const set: TrickplaySet = {
      intervalMs: info.Interval ?? 0,
      thumbWidth: info.Width ?? 0,
      thumbHeight: info.Height ?? 0,
      columns: info.TileWidth ?? 0,
      rows: info.TileHeight ?? 0,
      count: info.ThumbnailCount ?? 0,
    };
    if (set.intervalMs <= 0 || set.count <= 0 || set.columns <= 0 || set.rows <= 0) return null;
    return { kind: "jellyfin", server: this.base, itemId, width, token: this.token, set };
  }

  async externalSubtitles(itemId: string): Promise<ExternalSubtitle[]> {
    if (!this.subsByItem.has(itemId)) await this.storedSeekFrames(itemId);
    return this.subsByItem.get(itemId) ?? [];
  }

  constructor(
    private readonly client: JellyfinClient,
    session: JellyfinSession,
    clock: () => number = Date.now,
  ) {
    this.session = session;
    this.requestBase = preferredProbeUrl(session);
    this.index = new FilenameIndex(clock, MIN_REFRESH_MS);
  }

  session: JellyfinSession;

  get key(): string {
    return jellyfinKeyOf(this.session);
  }

  get displayName(): string {
    return this.session.serverName;
  }

  private get base() {
    return this.requestBase;
  }

  private get token() {
    return this.session.accessToken;
  }

  /** Prefer HTTPS again (e.g. on reconnect). */
  resetToPreferredProbe() {
    this.requestBase = preferredProbeUrl(this.session);
  }

  /** Session-only HTTP fallback after a plain-HTTP SSL failure. Does not persist. */
  async adoptPlainHttpFallback(error: unknown): Promise<boolean> {
    const http = await this.client.reachablePlainHttp(this.session, error);
    if (!http) return false;
    this.requestBase = http;
    return true;
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
    const views = await this.fetchViews();
    const items = (views.Items ?? [])
      .filter((v) => v.CollectionType == null || VIDEO_COLLECTIONS.has(v.CollectionType))
      .map((v) => this.toMediaItem(v, "LIBRARY"));
    this.librariesCache = { timestamp: now, items };
    return items;
  }

  private async fetchViews(): Promise<ItemsResult> {
    try {
      try {
        return await this.get("UserViews", [["userId", this.session.userId]]);
      } catch (e) {
        if (!(e instanceof MediaSourceError) || e.httpCode !== 404) throw e;
        return await this.get(`Users/${this.session.userId}/Views`);
      }
    } catch (e) {
      const probe = preferredProbeUrl(this.session);
      if (!(await this.adoptPlainHttpFallback(e))) throw e;
      try {
        return await this.client.get<ItemsResult>(this.requestBase, this.token, "UserViews", [
          ["userId", this.session.userId],
        ]);
      } catch (httpErr) {
        if (httpErr instanceof MediaSourceError && httpErr.httpCode === 404) {
          return await this.client.get<ItemsResult>(
            this.requestBase,
            this.token,
            `Users/${this.session.userId}/Views`,
          );
        }
        throw bothSchemesFailed(probe, e, this.requestBase, httpErr);
      }
    }
  }

  async children(parent: MediaItem): Promise<MediaItem[]> {
    if (parent.type === "LIBRARY" && parent.collectionType === "music") {
      return [
        mediaItem({ id: `${parent.id}:artists`, name: "Artists", type: "FOLDER", isFolder: true, collectionType: "music_artists", sourceKey: this.key }),
        mediaItem({ id: `${parent.id}:albums`, name: "Albums", type: "FOLDER", isFolder: true, collectionType: "music_albums", sourceKey: this.key }),
        mediaItem({ id: `${parent.id}:tracks`, name: "All Tracks", type: "FOLDER", isFolder: true, collectionType: "music_tracks", sourceKey: this.key }),
      ];
    }
    let dtos: BaseItemDto[];
    if (parent.collectionType === "music_artists") {
      dtos = await this.items([
        ["parentId", parent.id.replace(/:artists$/, "")],
        ["recursive", "true"],
        ["includeItemTypes", "MusicArtist"],
        ["sortBy", "SortName"],
        ["fields", LIST_FIELDS],
      ]);
    } else if (parent.collectionType === "music_albums") {
      dtos = await this.items([
        ["parentId", parent.id.replace(/:albums$/, "")],
        ["recursive", "true"],
        ["includeItemTypes", "MusicAlbum"],
        ["sortBy", "SortName"],
        ["fields", LIST_FIELDS],
      ]);
    } else if (parent.collectionType === "music_tracks") {
      dtos = await this.items([
        ["parentId", parent.id.replace(/:tracks$/, "")],
        ["recursive", "true"],
        ["includeItemTypes", "Audio"],
        ["sortBy", "SortName"],
        ["limit", "500"],
        ["fields", LIST_FIELDS],
      ]);
    } else if (parent.type === "ARTIST") {
      dtos = await this.items([
        ["artistIds", parent.id],
        ["recursive", "true"],
        ["includeItemTypes", "MusicAlbum,Audio"],
        ["sortBy", "IsFolder,ProductionYear,SortName"],
        ["sortOrder", "Descending"],
        ["fields", LIST_FIELDS],
      ]);
    } else if (parent.type === "ALBUM") {
      dtos = await this.items([
        ["parentId", parent.id],
        ["includeItemTypes", "Audio"],
        ["sortBy", "ParentIndexNumber,IndexNumber,SortName"],
        ["fields", LIST_FIELDS],
      ]);
    } else if (parent.type === "SEASON" && parent.seriesId !== null) {
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
      ["includeItemTypes", "Series,Movie,Episode,Video,MusicArtist,MusicAlbum,Audio"],
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
    this.rememberStreams(dto.Id, source);
    const isAudio = dto.Type === "Audio";
    const m = this.toMediaItem(dto);
    const title = m.artistName !== null && m.type === "TRACK" ? `${m.artistName} · ${m.name}` : m.seriesName !== null ? `${m.seriesName} · ${displayTitle(m)}` : displayTitle(m);
    return {
      itemId: dto.Id,
      url: this.streamUrl(dto.Id, source?.Id ?? null, isAudio),
      fileName: FileNames.baseName(path),
      sizeBytes: source?.Size ?? 0,
      durationSeconds: ticks / TICKS_PER_SECOND,
      title,
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
  streamUrl(itemId: string, mediaSourceId: string | null, isAudio = false): string {
    const query: Query = [["static", "true"]];
    if (mediaSourceId !== null) query.push(["mediaSourceId", mediaSourceId]);
    query.push(["api_key", this.token]);
    const endpoint = isAudio ? `Audio/${itemId}/stream` : `Videos/${itemId}/stream`;
    return this.client.url(this.base, endpoint, query).href;
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
          ["includeItemTypes", "Movie,Episode,Video,MusicVideo,Audio"],
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
    const artist = dto.Artists?.[0] ?? dto.AlbumArtist ?? dto.SeriesName ?? null;
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
      artistName: artist,
      albumName: dto.Album ?? null,
      albumId: dto.AlbumId ?? null,
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
    case "MusicArtist":
      return "ARTIST";
    case "MusicAlbum":
      return "ALBUM";
    case "Audio":
      return "TRACK";
    case "Folder":
    case "BoxSet":
      return "FOLDER";
    default:
      return dto.IsFolder === true ? "FOLDER" : "OTHER";
  }
}
