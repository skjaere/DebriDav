package io.skjaere.debridav.torrent.pgmq

data class TorrentHealthCheckMessage(
    val torrentId: Long
)

data class TorrentHealthRepairMessage(
    val torrentId: Long,
    val message: String
)
