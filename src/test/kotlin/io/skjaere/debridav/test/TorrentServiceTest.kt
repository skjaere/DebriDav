package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.torrent.TorrentService
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class TorrentServiceTest {
    @Test
    fun `that getNameFromMagnetWithoutContainerExtension() removes extension from torrent name`() {
        // given
        val name = "Release.Name.1080-Grp.mp4"
        val magnet = "magnet:?xt=urn:btih:hash&dn=$name&tr="

        //when
        val nameWithoutExtension = TorrentService.getNameFromMagnetWithoutContainerExtension(TorrentMagnet(magnet))

        //then
        assertEquals("Release.Name.1080-Grp", nameWithoutExtension)
    }

    @Test
    fun `that getNameFromMagnetWithoutContainerExtension() keeps name without extension intact`() {
        // given
        val name = "Release.Name.1080-Grp"
        val magnet = "magnet:?xt=urn:btih:hash&dn=$name&tr="

        //when
        val nameWithoutExtension = TorrentService.getNameFromMagnetWithoutContainerExtension(TorrentMagnet(magnet))

        //then
        assertEquals("Release.Name.1080-Grp", nameWithoutExtension)
    }

    @Test
    fun `that getHashFromMagnet() handles tracker URLs containing unencoded '?'`() {
        // given
        val hash = "1b261150f4fb43b90a8706463ea3078c6daaf51e"
        val magnet = "magnet:?xt=urn:btih:$hash" +
                "&dn=Release.Name.1080-Grp.mkv" +
                "&tr=http://tracker.example.com/announce.php?key=abc123"

        //when
        val result = TorrentService.getHashFromMagnet(TorrentMagnet(magnet))

        //then
        assertEquals(hash, result?.hash)
    }
}
