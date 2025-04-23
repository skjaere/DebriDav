package io.skjaere.debridav.debrid

import io.skjaere.debridav.torrent.TorrentHash
import io.skjaere.debridav.torrent.TorrentService

sealed interface CachedContentKey {
    fun getName(): String
}


data class UsenetRelease(val releaseName: String) : CachedContentKey {
    override fun getName() = releaseName
}


data class TorrentMagnet(val magnet: String) : CachedContentKey {
    override fun getName() = TorrentService.getNameFromMagnet(this) ?: "<no name>"
    fun getHash(): TorrentHash? = TorrentService.getHashFromMagnet(this)
}
