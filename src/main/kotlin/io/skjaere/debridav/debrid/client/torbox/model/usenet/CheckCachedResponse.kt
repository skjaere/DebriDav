package io.skjaere.debridav.debrid.client.torbox.model.usenet

import kotlinx.serialization.Serializable

@Serializable
data class CheckCachedResponse(
    val success: Boolean,
    val error: String?,
    val data: Map<String, HashIsCached>? = emptyMap()
)

@Serializable
data class HashIsCached(
    val name: String,
    val size: Long,
    val hash: String,
)
