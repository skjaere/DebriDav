package io.skjaere.debridav.debrid

sealed interface CachedContentKey
data class UsenetRelease(val releaseName: String) : CachedContentKey
data class TorrentMagnet(val magnet: String) : CachedContentKey
