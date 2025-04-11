package io.skjaere.debridav.debrid.client.torbox.model.torrent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TorrentListResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: TorrentListItem? = null
)

@Serializable
data class TorrentListItem(
    val id: Long,
    val hash: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val magnet: String? = null,
    val files: List<TorrentListItemFile>? = listOf()

)

@Serializable
data class TorrentListItemFile(
    val id: String,
    val md5: String? = null,
    @SerialName("s3_path") val s3Path: String,
    val name: String,
    val size: Long,
    @SerialName("mimetype") val mimeType: String,
    @SerialName("short_name") val shortName: String,
)
