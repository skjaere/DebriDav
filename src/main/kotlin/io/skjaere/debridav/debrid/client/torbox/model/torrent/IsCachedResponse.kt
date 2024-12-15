package io.skjaere.debridav.debrid.client.torbox.model.torrent

import kotlinx.serialization.Serializable

@Serializable
data class IsCachedResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: Map<String, IsCachedFile>? = null
)

@Serializable
data class IsCachedFile(
    val name: String,
    val size: Long,
    val hash: String,
)
