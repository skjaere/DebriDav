package io.skjaere.debridav.torrent.pgmq

data class TorrentHealthCheckMessage(
    val torrentId: Long
)

data class TorrentHealthRepairMessage(
    val torrentId: Long,
    val message: String,
    // Snapshot of the torrent's name + category at enqueue time so the repair-history
    // UI keeps a readable label even after the parent torrent row is pruned (by the
    // EmptyTorrentCleanupTask, by user action, by the repair flow itself). Without
    // this the LEFT JOIN to live data returns NULL and the frontend falls back to the
    // bare numeric id. Nullable for backwards-compat with messages enqueued before
    // this field existed.
    val name: String? = null,
    val category: String? = null,
)
