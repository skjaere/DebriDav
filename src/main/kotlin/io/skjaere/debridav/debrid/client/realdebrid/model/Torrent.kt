package io.skjaere.debridav.debrid.client.realdebrid.model

data class Torrent(
    val id: String,
    val name: String,
    val files: List<HostedFile>
)
