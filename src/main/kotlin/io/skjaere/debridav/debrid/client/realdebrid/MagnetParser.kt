package io.skjaere.debridav.debrid.client.realdebrid

object MagnetParser {
    fun getHashFromMagnet(magnet: String): String? {
        val params = magnet
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

