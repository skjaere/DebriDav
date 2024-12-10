package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.client.realdebrid.MagnetParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MagnetParsingTest {
    @Test
    fun extractsHashFromMagnetLink() {
        // given
        val magnet =
            "magnet:?xt=urn:btih:T3GUM5X5B4CHIFI2JN2KLFMPIJRZZ267&dn=ubuntu-23.10.1-desktop-amd64.iso" +
                    "&xl=5173995520&tr.1=https%3A%2F%2Ftorrent.ubuntu.com%2Fannounce" +
                    "&tr.2=https%3A%2F%2Ftorrent.ubuntu.com%2Fannounce" +
                    "&tr.3=https%3A%2F%2Fipv6.torrent.ubuntu.com%2Fannounce"


        // when
        val hash = MagnetParser.getHashFromMagnet(magnet)

        // then
        assertEquals("T3GUM5X5B4CHIFI2JN2KLFMPIJRZZ267", hash)
    }
}
