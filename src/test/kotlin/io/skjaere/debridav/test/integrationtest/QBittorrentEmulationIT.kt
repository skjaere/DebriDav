package io.skjaere.debridav.test.integrationtest

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.SECOND_MAGNET
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import io.skjaere.debridav.torrent.TorrentRepository
import io.skjaere.debridav.torrent.TorrentService
import io.skjaere.debridav.torrent.TorrentsInfoResponse
import kotlin.test.assertNotNull
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.hasSize
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
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class, TestSecurityConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
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

    @Autowired
    lateinit var torrentRepository: TorrentRepository

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    private val objectMapper = jacksonObjectMapper()

    @AfterEach
    fun tearDown() {
        mockserverClient.reset()
        try {
            sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/test")
        } catch (_: Throwable) {
        }
    }

    @Test
    fun `that torrents info endpoint points to correct location`() {
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
    fun `adding torrent produces moveable and deletable debrid file when torrent is cached`() {
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
            "http://localhost:${randomServerPort}/webdav/downloads/test/a/b/c/movie.mkv",
            "http://localhost:${randomServerPort}/webdav/movie.mkv"
        )
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("movie.mkv")
                )
            )
        )
        sardine.delete("http://localhost:${randomServerPort}/webdav/movie.mkv")
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/"), not(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("/movie.mkv")
                    )
                )
            )
        )
    }

    @Test
    fun `that existing file gets overwritten`() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()

        // when/then
        repeat(2) {
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
        }
    }

    @Test
    fun `that deleting torrent does not delete files`() {
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

        torrentRepository
            .getByHashIgnoreCase(TorrentService.getHashFromMagnet(TorrentMagnet(MAGNET))!!.hash)!!
            .let { torrent ->
                torrentRepository.delete(torrent)
            }

        // then
        assertNotNull(databaseFileService.getFileAtPath("/downloads/test/a/b/c/movie.mkv"))
    }

    @Test
    @Suppress("LongMethod")
    fun `that torrents info endpoint respects categories`() {
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

        val secondParts = MultipartBodyBuilder()
        secondParts.part("urls", SECOND_MAGNET)
        secondParts.part("category", "test2")
        secondParts.part("paused", "false")

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
            .body(BodyInserters.fromMultipartData(secondParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        val type = objectMapper.typeFactory.constructCollectionType(
            List::class.java,
            TorrentsInfoResponse::class.java
        )
        val testCategoryTorrentsInfoResponse = webTestClient.get()
            .uri("/api/v2/torrents/info?category=test")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult().responseBody
        val testCategoryParsedResponse: List<TorrentsInfoResponse> =
            objectMapper.readValue(testCategoryTorrentsInfoResponse, type)

        val test2CategoryTorrentsInfoResponse = webTestClient.get()
            .uri("/api/v2/torrents/info?category=test2")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult().responseBody
        val test2CategoryParsedResponse: List<TorrentsInfoResponse> =
            objectMapper.readValue(test2CategoryTorrentsInfoResponse, type)
        assertThat(
            testCategoryParsedResponse, allOf(
                hasSize(1),
                hasItems<TorrentsInfoResponse>(
                    hasProperty<TorrentsInfoResponse>(
                        "name", `is`("test")
                    ),
                    hasProperty<TorrentsInfoResponse>(
                        "category", `is`("test")
                    )
                )
            )
        )
        assertThat(
            test2CategoryParsedResponse, allOf(
                hasSize(1),
                hasItems<TorrentsInfoResponse>(
                    hasProperty<TorrentsInfoResponse>(
                        "name", `is`("second-name")
                    ),
                    hasProperty<TorrentsInfoResponse>(
                        "category", `is`("test2")
                    )
                )
            )
        )

        // finally
        sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/second-name")
    }

    @Test
    fun `that adding un-cached torrent with no mapped arr client configured results in 422 and no created torrent`() {
        // given
        torrentRepository.deleteAll()
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsNotCached()

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
            .expectStatus().isEqualTo(422)

        // then
        val type = objectMapper.typeFactory.constructCollectionType(
            List::class.java,
            TorrentsInfoResponse::class.java
        )
        val radarrCategoryTorrentsInfoResponse = webTestClient.get()
            .uri("/api/v2/torrents/info?category=test")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult().responseBody
        val radarrCategoryParsedResponse: List<TorrentsInfoResponse> =
            objectMapper.readValue(radarrCategoryTorrentsInfoResponse, type)
        assertThat(
            radarrCategoryParsedResponse, hasSize(0)
        )
    }

    @Test
    fun `that adding un-cached torrent with category mapped to arr client results in 422`() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "radarr")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsNotCached()

        // when/then
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is4xxClientError
    }

    @Test
    fun `that sending request without urls or torrents results in a 400`() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("category", "test")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsNotCached()

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
            .expectStatus().isEqualTo(400)
    }

    @Test
    fun `that torrents info endpoint returns all torrents when category is omitted`() {
        // Regression: previously this path force-unwrapped requestParams.category and
        // produced an NPE for any client polling /api/v2/torrents/info without a filter
        // (Sportarr's queue probe, Prowlarr health probes, plain qBittorrent UI).
        webTestClient.get()
            .uri("/api/v2/torrents/info")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
    }

    @Test
    fun `that auth login fails-closed when no WebDAV credentials are configured`() {
        // No webdav credentials configured in the integration test. Pre-1.0 minted a JWT
        // for any supplied username, which left /api/v2/** wide open by default. The
        // new contract: no creds = no login. qBittorrent's spec returns 200 + "Fails."
        // for failed logins (rather than a 4xx), so clients see the same surface as a
        // wrong-password attempt.
        webTestClient
            .post()
            .uri("/api/v2/auth/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("username=admin&password=adminadmin")
            .exchange()
            .expectStatus().isOk
            .expectCookie().doesNotExist("SID")
            .expectBody(String::class.java)
            .isEqualTo("Fails.")
    }

    @Test
    fun `that auth logout returns Ok and clears the SID cookie`() {
        // Cookie clearing semantics: empty value + max-age <= 0 tells the client to drop it.
        // Spring's WebTestClient surfaces a max-age=0 wire value as PT-1S after parsing, so
        // assert on the value being empty (the durable signal) rather than on max-age.
        webTestClient
            .post()
            .uri("/api/v2/auth/logout")
            .exchange()
            .expectStatus().isOk
            .expectCookie().value("SID", equalTo(""))
            .expectBody(String::class.java)
            .isEqualTo("Ok.")
    }
}
