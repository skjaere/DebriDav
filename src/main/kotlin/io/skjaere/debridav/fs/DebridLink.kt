package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.stream.StreamResult

data class DebridLink(
    val provider: DebridProvider,
    var link: String?,
    var lastChecked: Long,
    var lastStatus: StreamResult
)
