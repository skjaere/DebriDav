package io.skjaere.debridav.debrid

sealed interface DebridResponse {
    val provider: DebridProvider
}
