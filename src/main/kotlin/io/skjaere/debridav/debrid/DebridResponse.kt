package io.skjaere.debridav.debrid

import io.skjaere.debridav.fs.DebridProvider

sealed interface DebridResponse {
    val provider: DebridProvider
}
