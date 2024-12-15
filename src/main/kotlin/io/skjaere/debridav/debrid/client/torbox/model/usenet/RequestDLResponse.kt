package io.skjaere.debridav.debrid.client.torbox.model.usenet

import kotlinx.serialization.Serializable

@Serializable
data class RequestDLResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: String?
)
