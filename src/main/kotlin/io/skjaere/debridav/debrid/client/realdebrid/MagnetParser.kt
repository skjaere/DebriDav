package io.skjaere.debridav.debrid.client.realdebrid

import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.torrent.TorrentService

object MagnetParser {
    /** The magnet's `xt=urn:btih:` value as given (hex or base32); see [TorrentService.getBtihFromMagnet]. */
    fun getHashFromMagnet(magnet: TorrentMagnet): String? = TorrentService.getBtihFromMagnet(magnet)
}
