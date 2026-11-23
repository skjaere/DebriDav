package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.client.realdebrid.MagnetParser
import io.skjaere.debridav.torrent.TorrentService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val BTIH = "0123456789abcdef0123456789abcdef01234567"
private const val BTMH = "1220aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

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
        val hash = MagnetParser.getHashFromMagnet(TorrentMagnet(magnet))

        // then
        assertEquals("T3GUM5X5B4CHIFI2JN2KLFMPIJRZZ267", hash)
    }

    @Test
    fun `a hybrid magnet's v1 hash is found whichever xt comes first`() {
        // Hybrid (v1 + v2) torrents carry both xt parameters; clients usually put btih first.
        for (magnet in listOf(
            "magnet:?xt=urn:btih:$BTIH&xt=urn:btmh:$BTMH&dn=hybrid",
            "magnet:?xt=urn:btmh:$BTMH&xt=urn:btih:$BTIH&dn=hybrid",
        )) {
            assertEquals(BTIH, TorrentService.getHashFromMagnet(TorrentMagnet(magnet))?.hash, magnet)
            assertEquals(BTIH, MagnetParser.getHashFromMagnet(TorrentMagnet(magnet)), magnet)
        }
    }

    @Test
    fun `a magnet without a v1 hash has none`() {
        val v2Only = TorrentMagnet("magnet:?xt=urn:btmh:$BTMH&dn=v2-only")

        assertNull(TorrentService.getHashFromMagnet(v2Only))
        assertNull(MagnetParser.getHashFromMagnet(v2Only))
    }

    @Test
    fun `the TorBox parser tolerates parameters without a value`() {
        assertEquals(BTIH, MagnetParser.getHashFromMagnet(TorrentMagnet("magnet:?xt=urn:btih:$BTIH&dn&tr=")))
    }
}
