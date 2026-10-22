package com.yarmiplaytv.media.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class PlexPin(
    val id: Long,
    val code: String,
    val authToken: String? = null,
    val expiresIn: Int? = null,
)

@Serializable
data class PlexUser(
    val username: String? = null,
    val title: String? = null,
)

@Serializable
data class PlexResource(
    val name: String = "",
    val product: String? = null,
    val provides: String? = null,
    val clientIdentifier: String = "",
    val accessToken: String? = null,
    val owned: Boolean? = null,
    val connections: List<PlexResourceConnection> = emptyList(),
)

@Serializable
data class PlexResourceConnection(
    val uri: String,
    val local: Boolean = false,
    val relay: Boolean = false,
)

@Serializable
data class PlexResponse(
    @SerialName("MediaContainer") val container: PlexContainer = PlexContainer(),
)

@Serializable
data class PlexContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    val machineIdentifier: String? = null,
    val friendlyName: String? = null,
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
    @SerialName("Hub") val hubs: List<PlexHub> = emptyList(),
)

@Serializable
data class PlexDirectory(
    val key: String = "",
    val title: String? = null,
    val type: String? = null,
    val thumb: String? = null,
    val art: String? = null,
)

@Serializable
data class PlexHub(
    val type: String? = null,
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
)

@Serializable
data class PlexMetadata(
    val ratingKey: String,
    val type: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val year: Int? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val parentRatingKey: String? = null,
    val parentTitle: String? = null,
    val grandparentRatingKey: String? = null,
    val grandparentTitle: String? = null,
    val duration: Long? = null,
    val thumb: String? = null,
    val viewCount: Int? = null,
    val viewOffset: Long? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
    /** With `includeMarkers=1`: where the intro and credits are (Plex detects them). */
    @SerialName("Marker") val markers: List<PlexMarker> = emptyList(),
)

@Serializable
data class PlexMarker(
    /** intro, credits or commercial. */
    val type: String? = null,
    /** Milliseconds. */
    val startTimeOffset: Long = 0,
    val endTimeOffset: Long = 0,
)

@Serializable
data class PlexMedia(
    val duration: Long? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

@Serializable
data class PlexPart(
    val key: String? = null,
    val file: String? = null,
    val size: Long? = null,
    val duration: Long? = null,
    @SerialName("Stream") val streams: List<PlexStream> = emptyList(),
)

@Serializable
data class PlexStream(
    val id: Long? = null,
    /** 1 video, 2 audio, 3 subtitle. */
    val streamType: Int? = null,
    val codec: String? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val displayTitle: String? = null,
    /** Set for a subtitle file beside the video (`/library/streams/…`), not for one inside it. */
    val key: String? = null,
)

/** Plex answers `Index` as one object or a list. */
object PlexIndexListSerializer : JsonTransformingSerializer<List<PlexIndex>>(ListSerializer(PlexIndex.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = if (element is JsonArray) element else JsonArray(listOf(element))
}

@Serializable
data class PlexIndex(val id: String? = null)

@Serializable
data class PlexIndexesBody(
    @SerialName("MediaContainer") val container: PlexIndexesContainer = PlexIndexesContainer(),
)

@Serializable
data class PlexIndexesContainer(
    @Serializable(with = PlexIndexListSerializer::class)
    @SerialName("Index") val indexes: List<PlexIndex> = emptyList(),
)
