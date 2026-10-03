package com.yarmiplaytv.media.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)
