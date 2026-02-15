package io.skjaere.debridav.usenet

data class NzbImportTaskData(
    val nzbBytesBase64: String,
    val usenetDownloadId: Long
)
