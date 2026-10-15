/**
 * What Jellyfin's REST API answers (JellyfinDtos.kt), under its own PascalCase names. Every field is optional: the
 * answers are read defensively, as the Kotlin's lenient JSON reads them.
 */

export interface PublicSystemInfo {
  ServerName?: string | null;
  Version?: string | null;
  Id?: string | null;
  LocalAddress?: string | null;
  StartupWizardCompleted?: boolean | null;
}

export interface QuickConnectResult {
  Authenticated?: boolean | null;
  Secret?: string | null;
  Code?: string | null;
}

export interface AuthenticationResult {
  User?: UserDto | null;
  AccessToken?: string | null;
  ServerId?: string | null;
}

export interface UserDto {
  Id: string;
  Name?: string | null;
}

/** `GET MediaSegments/{itemId}` (Jellyfin 10.10+): the item's intro, outro and other marked parts. */
export interface MediaSegmentsResult {
  Items?: MediaSegmentDto[] | null;
}

export interface MediaSegmentDto {
  /** Intro, Outro, Recap, Preview or Commercial. */
  Type?: string | null;
  StartTicks?: number | null;
  EndTicks?: number | null;
}

export interface ItemsResult {
  Items?: BaseItemDto[] | null;
  TotalRecordCount?: number | null;
}

export interface BaseItemDto {
  Id: string;
  Name?: string | null;
  Type?: string | null;
  IsFolder?: boolean | null;
  CollectionType?: string | null;
  Overview?: string | null;
  ProductionYear?: number | null;
  IndexNumber?: number | null;
  ParentIndexNumber?: number | null;
  SeriesName?: string | null;
  SeriesId?: string | null;
  SeasonId?: string | null;
  Album?: string | null;
  AlbumId?: string | null;
  AlbumArtist?: string | null;
  Artists?: string[] | null;
  RunTimeTicks?: number | null;
  Path?: string | null;
  MediaSources?: MediaSourceInfo[] | null;
  ImageTags?: Record<string, string> | null;
  UserData?: UserItemData | null;
  /** External ids by provider ("Tvdb", "Imdb", and with anime plugins "MyAnimeList", "AniList", "AniDB"). */
  ProviderIds?: Record<string, string> | null;
}

export interface MediaSourceInfo {
  Id?: string | null;
  Path?: string | null;
  Name?: string | null;
  Container?: string | null;
  Size?: number | null;
  RunTimeTicks?: number | null;
  Protocol?: string | null;
}

export interface UserItemData {
  Played?: boolean | null;
  PlaybackPositionTicks?: number | null;
}

export interface AuthenticateByNameBody {
  Username: string;
  Pw: string;
}

export interface PlaybackProgressBody {
  ItemId: string;
  MediaSourceId?: string;
  PlaySessionId: string;
  PositionTicks: number;
  IsPaused: boolean;
  CanSeek: boolean;
  PlayMethod: string;
}

export interface QuickConnectAuthBody {
  Secret: string;
}
