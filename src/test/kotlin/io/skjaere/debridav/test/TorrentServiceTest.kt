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
}
