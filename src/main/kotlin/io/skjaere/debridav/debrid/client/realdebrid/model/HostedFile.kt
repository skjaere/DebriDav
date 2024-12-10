package io.skjaere.debridav.debrid.client.realdebrid.model

data class HostedFile(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val link: String?
)
