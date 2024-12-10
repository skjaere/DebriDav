package io.skjaere.debridav.debrid.client.realdebrid.model

import kotlinx.serialization.Serializable

@Serializable
data class UnrestrictedLink(
    val id: String,
    val filename: String,
    val mimeType: String,
    val filesize: Long,
    val link: String,
    val host: String,
    val download: String
)
