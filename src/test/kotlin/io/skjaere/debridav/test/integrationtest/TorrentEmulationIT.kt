/*
package io.skjaere.debridav.test.integrationtest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.qbittorrent.TorrentsInfoResponse
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Duration

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class TorrentEmulationIT {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var premiumizeStubbingService: PremiumizeStubbingService

    @Autowired
    private lateinit var fileService: FileService

    private val objectMapper = jacksonObjectMapper()

    @AfterEach
    fun tearDown() {
        fileService.deleteFile("/downloads/test/a/b/c/movie.mkv")
    }

    @Test
    fun torrentsInfoEndpointPointsToCorrectLocation() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()

        // when
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        val type = objectMapper.typeFactory.constructCollectionType(
            List::class.java,
            TorrentsInfoResponse::class.java
        )
        val torrentsInfoResponse = webTestClient.get()
            .uri("/api/v2/torrents/info?category=test")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult().responseBody

        val parsedResponse: List<TorrentsInfoResponse> =
            objectMapper.readValue(torrentsInfoResponse, type)

        assertEquals("/data/downloads/test", parsedResponse.first().contentPath)
    }

    @Test
    fun addingTorrentProducesDebridFileWhenTorrentCached() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()

        // when
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        val debridFile = fileService.getDebridFileContents("/downloads/test/a/b/c/movie.mkv")

        // then

        assertEquals(
            "http://localhost:${premiumizeStubbingService.port}/workingLink",
            (debridFile!!.debridLinks.first() as CachedFile).link
        )
    }
}
*/
