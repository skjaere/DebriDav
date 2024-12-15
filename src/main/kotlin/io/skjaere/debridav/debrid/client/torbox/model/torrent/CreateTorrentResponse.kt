package io.skjaere.debridav.debrid.client.torbox.model.torrent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class CreateTorrentResponse(
    val success: Boolean,
    val error: String?,
    val details: String? = null,
    val data: CreatedTorrent

)

@Serializable
data class CreatedTorrent(
    @SerialName("torrent_id") val torrentId: String,
    val name: String? = null
)
