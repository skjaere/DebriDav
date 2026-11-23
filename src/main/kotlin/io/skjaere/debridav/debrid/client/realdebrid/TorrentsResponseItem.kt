package io.skjaere.debridav.debrid.client.realdebrid

import kotlinx.serialization.Serializable

@Serializable
data class TorrentsResponseItem(
    val id: String,
    val filename: String,
    val hash: String,
    val bytes: Long,
    val links: List<String>,
    /**
     * RD-side status; common values are `downloaded`, `downloading`, `queued`,
     * `magnet_error`, `error`, `dead`, `virus`, `compressing`, `uploading`. We treat
     * `downloaded` as "available for streaming"; everything else means RD can't currently
     * serve the torrent. Nullable for forward compatibility / older sync rows.
     */
    val status: String? = null,
)
