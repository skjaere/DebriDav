package io.skjaere.debridav.debrid.client.realdebrid

import kotlinx.serialization.Serializable

@Serializable
data class TorrentsResponseItem(
    val id: String,
    val filename: String,
    val hash: String,
    val bytes: Long,
    val links: List<String>
)
