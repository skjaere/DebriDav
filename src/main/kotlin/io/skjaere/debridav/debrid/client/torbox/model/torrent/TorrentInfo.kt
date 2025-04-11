package io.skjaere.debridav.debrid.client.torbox.model.torrent

import kotlinx.serialization.Serializable

@Serializable
data class TorrentInfoResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: TorrentInfo?
)

@Serializable
data class TorrentInfo(
    val name: String,
    val hash: String,
    val size: Long,
    val files: List<TorrentInfoFile>
)

@Serializable
data class TorrentInfoFile(
    val name: String,
    val size: Long
)
