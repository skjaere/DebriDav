package io.skjaere.debridav.test

import io.skjaere.debridav.torrent.TorrentToMagnetConverter
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import java.nio.charset.StandardCharsets

class TorrentToMagnetConverterTest {
    private val underTest = TorrentToMagnetConverter()
    private val resourceLoader = DefaultResourceLoader()

    @Test
    fun `that torrent is converted to magnet correctly`() {
        //when
        val magnet = underTest.convertTorrentToMagnet(
            resourceLoader.getResource("classpath:ubuntu-24.10-desktop-amd64.iso.torrent").file.readBytes()
        )

        // then
        assertEquals(
            "magnet:?xt=urn:btih:3f9aac158c7de8dfcab171ea58a17aabdf7fbc93&dn=ubuntu-24.10-desktop-amd64.iso&tr=${
                java.net.URLEncoder.encode(
                    "https://torrent.ubuntu.com/announce",
                    StandardCharsets.UTF_8
                )
            }",
            magnet
        )
    }
}
