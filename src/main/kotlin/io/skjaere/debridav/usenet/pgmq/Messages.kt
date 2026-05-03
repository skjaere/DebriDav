package io.skjaere.debridav.usenet.pgmq

data class NzbImportMessage(
    val nzbBytesBase64: String,
    val usenetDownloadId: Long,
    val nzbImportRecordId: Long
)

data class NzbHealthCheckMessage(
    val nzbDocumentId: Long
)

data class NzbHealthRepairMessage(
    val nzbDocumentId: Long,
    val message: String,
    // Snapshot of the document's name + category at enqueue time so the repair-history
    // UI keeps a readable label even after the parent nzb_document is pruned (by the
    // EmptyNzbDocumentCleanupTask, by user action, by the repair flow itself). Without
    // this the LEFT JOIN to live data returns NULL and the frontend falls back to the
    // bare numeric id. Nullable for backwards-compat with messages enqueued before
    // this field existed.
    val name: String? = null,
    val category: String? = null,
)
