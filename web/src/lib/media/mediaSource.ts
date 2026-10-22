import * as FileNames from "./fileNames";
import type { ExternalSubtitle, StoredSeekFrames } from "./seekThumbs";
import type { SkipSegment } from "./skipSegment";

/**
 * A browsable library of video files (Jellyfin, Plex), as MediaSource.kt has it. The browser reaches the servers
 * directly with fetch (both answer CORS with `*`); every call is async and may throw a [MediaSourceError].
 */
export interface MediaSource {
  /** Identifies one server (e.g. "jellyfin:<server id>"); items and playables carry it as their `sourceKey`. */
  readonly key: string;
  /** The backend: "jellyfin", "plex", or "all" for several servers browsed as one. */
  readonly kind: string;
  readonly displayName: string;

  /** Top-level entry points (libraries / views). */
  libraries(): Promise<MediaItem[]>;

  /** Direct children of a folder-like item (library, series, season, folder). */
  children(parent: MediaItem): Promise<MediaItem[]>;

  search(query: string, limit?: number): Promise<MediaItem[]>;

  /** Continue-watching / recently added style shortcuts for the home screen. */
  recent(limit?: number): Promise<MediaItem[]>;

  /** Resolves a playable stream (URL plus the real file name/size used for Syncplay matching). */
  playable(item: MediaItem): Promise<PlayableMedia>;

  /**
   * Finds the library item whose underlying file matches [fileName] (as it appears in a Syncplay shared playlist).
   * Equivalent to the desktop client's media-directory lookup.
   */
  resolveByFilename(fileName: string): Promise<ResolveResult>;

  /**
   * The item whose file is named exactly [fileName] (case-insensitive), from the filename index only: never a title
   * search, never a stream. Null when there's no such file.
   */
  findExact(fileName: string): Promise<Found | null>;

  /** Tells the server how far playback of one of its items got; failures throw [MediaSourceError]. */
  reportPlayback(report: PlaybackReport): Promise<void>;

  /**
   * Where item [itemId]'s opening and ending are, as the server marked them (Jellyfin's media segments, Plex's intro
   * and credits markers); empty when it has none. Failures throw [MediaSourceError].
   */
  skipSegments(itemId: string): Promise<SkipSegment[]>;

  /**
   * The episodes of the season episode [itemId] is in, [itemId] too, ready to stream (for comparing their sound to
   * find the opening and ending); empty when it isn't an episode or the server can't say.
   */
  seasonEpisodes(itemId: string): Promise<PlayableMedia[]>;

  imageUrl(item: MediaItem, maxWidth?: number): string | null;

  /** Frames the server stored for the seek bar, or null when it never generated them. */
  storedSeekFrames?(itemId: string): Promise<StoredSeekFrames | null>;

  /** Subtitle files the server lists beside the video, separate from the tracks inside it. */
  externalSubtitles?(itemId: string): Promise<ExternalSubtitle[]>;
}

export type ReportState = "STARTED" | "PLAYING" | "PAUSED" | "STOPPED";

export interface PlaybackReport {
  itemId: string;
  /** One per playback; Plex's X-Plex-Session-Identifier, Jellyfin's PlaySessionId. */
  sessionId: string;
  state: ReportState;
  positionSeconds: number;
  durationSeconds: number;
  markWatched: boolean;
}

export type MediaItemType = "LIBRARY" | "FOLDER" | "SERIES" | "SEASON" | "EPISODE" | "MOVIE" | "VIDEO" | "ARTIST" | "ALBUM" | "TRACK" | "OTHER";

/** A library entry: a plain object, so lists of them can go straight into Svelte state. */
export interface MediaItem {
  id: string;
  name: string;
  type: MediaItemType;
  isFolder: boolean;
  collectionType: string | null;
  overview: string | null;
  year: number | null;
  indexNumber: number | null;
  parentIndexNumber: number | null;
  seriesName: string | null;
  seriesId: string | null;
  artistName: string | null;
  albumName: string | null;
  albumId: string | null;
  durationSeconds: number | null;
  path: string | null;
  imageTag: string | null;
  played: boolean;
  resumeSeconds: number;
  sourceKey: string;
}

/** A [MediaItem] with the defaults MediaItem.kt has for everything but its id, name, type and folder flag. */
export const mediaItem = (m: Partial<MediaItem> & Pick<MediaItem, "id" | "name" | "type" | "isFolder">): MediaItem => ({
  collectionType: null,
  overview: null,
  year: null,
  indexNumber: null,
  parentIndexNumber: null,
  seriesName: null,
  seriesId: null,
  artistName: null,
  albumName: null,
  albumId: null,
  durationSeconds: null,
  path: null,
  imageTag: null,
  played: false,
  resumeSeconds: 0,
  sourceKey: "",
  ...m,
});

/** Unique across servers (ids alone may collide between Jellyfin and Plex). */
export const uniqueKey = (item: Pick<MediaItem, "sourceKey" | "id">): string => `${item.sourceKey}/${item.id}`;

const PLAYABLE_TYPES: ReadonlySet<MediaItemType> = new Set(["EPISODE", "MOVIE", "VIDEO", "TRACK"]);

export const isPlayable = (item: MediaItem): boolean => !item.isFolder && PLAYABLE_TYPES.has(item.type);

const pad2 = (n: number) => String(n).padStart(2, "0");

/** Display title, e.g. "S01E01 · The Goddess (Neptune) Of Planeptune". */
export function displayTitle(item: MediaItem): string {
  if (item.type !== "EPISODE" || item.indexNumber === null) return item.name;
  const s = item.parentIndexNumber !== null ? `S${pad2(item.parentIndexNumber)}` : "";
  return `${s}E${pad2(item.indexNumber)} · ${item.name}`;
}

/** The item's file name, without its folders; null when the server didn't say where the file is. */
export const fileNameOf = (item: MediaItem): string | null => (item.path === null ? null : FileNames.baseName(item.path));

export interface PlayableMedia {
  itemId: string;
  /** Answers HTTP Range requests (the player reads it with HttpSource), with the server's token in the query. */
  url: string;
  /** File name exactly as on disk; this is what Syncplay peers compare against. */
  fileName: string;
  sizeBytes: number;
  durationSeconds: number;
  title: string;
  /** The [MediaSource.key] this comes from; empty for local files. */
  sourceKey: string;
  /** Which episode of which show this is, when known (for list tracking such as MyAnimeList). */
  episode: EpisodeInfo | null;
}

/**
 * An episode as the library (or its file name) describes it. [episode] may be counted from the start of the show
 * ("absolute") or from the start of [season]; matching decides which.
 */
export interface EpisodeInfo {
  seriesName: string;
  season: number | null;
  episode: number | null;
  year: number | null;
  /** Identifies the show across its episodes: the server's series id, or the normalized title for files. */
  seriesKey: string;
  /** The MyAnimeList id when the media server knows it (anime metadata plugins). */
  malId: number | null;
}

export type MatchKind = "EXACT_FILENAME" | "NORMALIZED_FILENAME" | "PARSED_SEARCH";

export interface Found {
  kind: "found";
  item: MediaItem;
  playable: PlayableMedia;
  matchedBy: MatchKind;
}

export interface NotFound {
  kind: "notFound";
  fileName: string;
  reason: string;
}

export type ResolveResult = Found | NotFound;

export const found = (item: MediaItem, playable: PlayableMedia, matchedBy: MatchKind): Found => ({ kind: "found", item, playable, matchedBy });
export const notFound = (fileName: string, reason: string): NotFound => ({ kind: "notFound", fileName, reason });

/** A server call that failed; [httpCode] is the HTTP status when the server answered, null when it couldn't be reached. */
export class MediaSourceError extends Error {
  constructor(
    message: string,
    readonly httpCode: number | null = null,
    options?: { cause?: unknown },
  ) {
    super(message, options);
    this.name = "MediaSourceError";
  }
}

/** The message of whatever was thrown, for showing to the user. */
export const messageOf = (e: unknown): string => (e instanceof Error ? e.message : String(e));
