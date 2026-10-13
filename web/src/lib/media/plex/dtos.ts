/**
 * What plex.tv and a Plex Media Server answer with `Accept: application/json` (PlexDtos.kt). Every field but the ids
 * is optional: the answers are read defensively, as the Kotlin's lenient JSON reads them.
 */

export interface PlexPin {
  id: number;
  code: string;
  authToken?: string | null;
  expiresIn?: number | null;
}

export interface PlexUser {
  username?: string | null;
  title?: string | null;
}

export interface PlexResource {
  name?: string;
  product?: string | null;
  provides?: string | null;
  clientIdentifier?: string;
  accessToken?: string | null;
  owned?: boolean | null;
  connections?: PlexResourceConnection[] | null;
}

export interface PlexResourceConnection {
  uri: string;
  local?: boolean;
  relay?: boolean;
  /** "https" for the `*.plex.direct` addresses `includeHttps=1` adds, "http" for plain ones. */
  protocol?: string | null;
}

export interface PlexResponse {
  MediaContainer?: PlexContainer | null;
}

export interface PlexContainer {
  size?: number;
  totalSize?: number | null;
  machineIdentifier?: string | null;
  friendlyName?: string | null;
  Directory?: PlexDirectory[] | null;
  Metadata?: PlexMetadata[] | null;
  Hub?: PlexHub[] | null;
}

export interface PlexDirectory {
  key?: string;
  title?: string | null;
  type?: string | null;
  thumb?: string | null;
  art?: string | null;
}

export interface PlexHub {
  type?: string | null;
  Metadata?: PlexMetadata[] | null;
}

export interface PlexMetadata {
  ratingKey: string;
  type?: string | null;
  title?: string | null;
  summary?: string | null;
  year?: number | null;
  index?: number | null;
  parentIndex?: number | null;
  parentRatingKey?: string | null;
  parentTitle?: string | null;
  grandparentRatingKey?: string | null;
  grandparentTitle?: string | null;
  duration?: number | null;
  thumb?: string | null;
  viewCount?: number | null;
  viewOffset?: number | null;
  leafCount?: number | null;
  viewedLeafCount?: number | null;
  Media?: PlexMedia[] | null;
  /** With `includeMarkers=1`: where the intro and credits are (Plex detects them). */
  Marker?: PlexMarker[] | null;
}

export interface PlexMarker {
  /** intro, credits or commercial. */
  type?: string | null;
  /** Milliseconds. */
  startTimeOffset?: number | null;
  endTimeOffset?: number | null;
}

export interface PlexMedia {
  duration?: number | null;
  Part?: PlexPart[] | null;
}

export interface PlexPart {
  key?: string | null;
  file?: string | null;
  size?: number | null;
  duration?: number | null;
}
