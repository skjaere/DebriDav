package io.skjaere.debridav.test.integrationtest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.skjaere.debridav.DebridApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.integrationtest.config.TestContextInitializer.Companion.BASE_PATH
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.io.File
import java.time.Duration

@SpringBootTest(
    classes = [DebridApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class TorrentEmulationIT {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var premiumizeStubbingService: PremiumizeStubbingService

    private val objectMapper = jacksonObjectMapper()

    @AfterEach
    fun tearDown() {
        File(BASE_PATH).deleteRecursively()
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
            io.skjaere.debridav.qbittorrent.TorrentsInfoResponse::class.java
        )
        val torrentsInfoResponse = webTestClient.get()
            .uri("/api/v2/torrents/info?category=test")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult().responseBody
        val parsedResponse: List<io.skjaere.debridav.qbittorrent.TorrentsInfoResponse> =
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
        val debridFile = File("/tmp/debridavtests/downloads/test/a/b/c/movie.mkv.debridfile")
        val debridFileContents: DebridFileContents = Json.decodeFromString(debridFile.readText())

        // then
        assertTrue(debridFile.exists())
        assertEquals(
            "http://localhost:${premiumizeStubbingService.port}/workingLink",
            (debridFileContents.debridLinks.first() as CachedFile).link
        )

        debridFile.delete()
    }
}
