package com.yarmiplaytv.media.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("LocalAddress") val localAddress: String? = null,
    @SerialName("StartupWizardCompleted") val startupWizardCompleted: Boolean? = null,
)

@Serializable
data class QuickConnectResult(
    @SerialName("Authenticated") val authenticated: Boolean = false,
    @SerialName("Secret") val secret: String = "",
    @SerialName("Code") val code: String = "",
)

@Serializable
data class AuthenticationResult(
    @SerialName("User") val user: UserDto? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
)

/** `GET MediaSegments/{itemId}` (Jellyfin 10.10+): the item's intro, outro and other marked parts. */
@Serializable
data class MediaSegmentsResult(
    @SerialName("Items") val items: List<MediaSegmentDto> = emptyList(),
)

@Serializable
data class MediaSegmentDto(
    /** Intro, Outro, Recap, Preview or Commercial. */
    @SerialName("Type") val type: String? = null,
    @SerialName("StartTicks") val startTicks: Long = 0,
    @SerialName("EndTicks") val endTicks: Long = 0,
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("Artists") val artists: List<String>? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo>? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("UserData") val userData: UserItemData? = null,
    /** External ids by provider ("Tvdb", "Imdb", and with anime plugins "MyAnimeList", "AniList", "AniDB"). */
    @SerialName("ProviderIds") val providerIds: Map<String, String>? = null,
)

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Protocol") val protocol: String? = null,
    /** Seek-bar sprite sheets, keyed by thumbnail width, when the server has generated them. */
    @SerialName("Trickplay") val trickplay: Map<String, TrickplayInfoDto>? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto>? = null,
)

@Serializable
data class TrickplayInfoDto(
    @SerialName("Width") val width: Int = 0,
    @SerialName("Height") val height: Int = 0,
    @SerialName("TileWidth") val tileWidth: Int = 0,
    @SerialName("TileHeight") val tileHeight: Int = 0,
    @SerialName("ThumbnailCount") val thumbnailCount: Int = 0,
    @SerialName("Interval") val interval: Int = 0,
)

@Serializable
data class MediaStreamDto(
    @SerialName("Type") val type: String? = null,
    @SerialName("Index") val index: Int? = null,
    @SerialName("IsExternal") val isExternal: Boolean? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Codec") val codec: String? = null,
)

@Serializable
data class UserItemData(
    @SerialName("Played") val played: Boolean? = null,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
)

@Serializable
internal data class AuthenticateByNameBody(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
internal data class PlaybackProgressBody(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PlaySessionId") val playSessionId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
)

@Serializable
internal data class QuickConnectAuthBody(
    @SerialName("Secret") val secret: String,
)
