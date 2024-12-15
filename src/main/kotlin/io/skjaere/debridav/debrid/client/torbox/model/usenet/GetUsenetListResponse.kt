package io.skjaere.debridav.debrid.client.torbox.model.usenet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GetUsenetListResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: GetUsenetListItem? = null
)

@Serializable
data class GetUsenetListItem(
    val id: Int,
    val hash: String,
    @SerialName("auth_id") val authId: String,
    val name: String,
    @SerialName("download_state") val downloadState: String,
    @SerialName("download_speed") val downloadSpeed: Double,
    val eta: Long,
    val progress: Double,
    val size: Long,
    @SerialName("download_id") val downloadId: String,
    val active: Boolean,
    val cached: Boolean,
    @SerialName("download_present") val downloadPresent: Boolean,
    @SerialName("download_finished") val downloadFinished: Boolean,
    @SerialName("expires_at") val expiresAt: String? = null,
    val server: Int? = null,
    val files: List<GetUsenetResponseListItemFile>
)

@Serializable
data class GetUsenetResponseListItemFile(
    val id: String,
    val md5: String? = null,
    val hash: String,
    val name: String,
    val size: Long,
    val zipped: Boolean,
    @SerialName("s3_path") val s3Path: String,
    val infected: Boolean,
    val mimetype: String,
    @SerialName("short_name") val shortName: String,
    @SerialName("absolute_path") val absolutePath: String,
)
