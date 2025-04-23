package io.skjaere.debridav.debrid.client.realdebrid

import io.skjaere.debridav.debrid.TorrentMagnet

object MagnetParser {
    fun getHashFromMagnet(magnet: TorrentMagnet): String? {
        val params = magnet
            .magnet
            .replace("magnet:", "")
            .replace("?", "")
            .split("&")
            .associate {
                val pair = it.split("=")
                pair[0] to pair[1]
            }
        return params["xt"]?.replace("urn:btih:", "")
    }
}

