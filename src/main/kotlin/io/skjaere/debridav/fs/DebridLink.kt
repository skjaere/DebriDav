package io.skjaere.debridav.fs

import io.skjaere.debridav.StreamingService.Result

data class DebridLink(
    val provider: DebridProvider,
    var link: String?,
    var lastChecked: Long,
    var lastStatus: Result
)
