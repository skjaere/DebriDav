package io.skjaere.debridav.test.integrationtest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Duration


@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@MockServerTest
class QBittorrentEmulationIT {
    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var premiumizeStubbingService: PremiumizeStubbingService

    @Autowired
    lateinit var mockserverClient: ClientAndServer

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    private val objectMapper = jacksonObjectMapper()

    @AfterEach
    fun tearDown() {
        mockserverClient.reset()
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
            io.skjaere.debridav.torrent.TorrentsInfoResponse::class.java
        )
        val torrentsInfoResponse = webTestClient.get()
            .uri("/api/v2/torrents/info?category=test")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult().responseBody
        val parsedResponse: List<io.skjaere.debridav.torrent.TorrentsInfoResponse> =
            objectMapper.readValue(torrentsInfoResponse, type)

        assertEquals("/data/downloads/test", parsedResponse.first().contentPath)
        sardine.delete("http://localhost:${randomServerPort}/downloads/test")
    }

    @Test
    fun addingTorrentProducesMoveableAndDeletableDebridFileWhenTorrentCached() {
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
        val debridFileContents: DebridFileContents? =
            (databaseFileService.getFileAtPath("/downloads/test/a/b/c/movie.mkv") as RemotelyCachedEntity).contents

        // then
        assertEquals(
            "http://localhost:${premiumizeStubbingService.port}/workingLink",
            (debridFileContents?.debridLinks!!.first() as CachedFile).link
        )
        sardine.move(
            "http://localhost:${randomServerPort}/downloads/test/a/b/c/movie.mkv",
            "http://localhost:${randomServerPort}/movie.mkv"
        )
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("movie.mkv")
                )
            )
        )
        sardine.delete("http://localhost:${randomServerPort}/movie.mkv")
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/"), not(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("/movie.mkv")
                    )
                )
            )
        )
    }
}
