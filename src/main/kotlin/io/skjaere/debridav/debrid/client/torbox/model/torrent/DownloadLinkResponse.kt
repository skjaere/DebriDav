package io.skjaere.debridav.debrid.client.torbox.model.torrent

import kotlinx.serialization.Serializable

@Serializable
data class DownloadLinkResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: String

)
