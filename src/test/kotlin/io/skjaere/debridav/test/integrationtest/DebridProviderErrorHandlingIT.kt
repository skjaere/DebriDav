package io.skjaere.debridav.test.integrationtest

import io.ktor.client.HttpClient
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfiguration
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.ClientError
import io.skjaere.debridav.debrid.model.NetworkError
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.integrationtest.config.ContentStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import io.skjaere.debridav.test.integrationtest.config.RealDebridClientProxy
import io.skjaere.debridav.test.integrationtest.config.RealDebridStubbingService
import io.skjaere.debridav.test.integrationtest.config.TestContextInitializer.Companion.BASE_PATH
import kotlin.test.assertFalse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.io.File
import java.time.Duration

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        [
            "debridav.debrid-clients=real_debrid,premiumize",
            "debridav.connect-timeout-milliseconds=250",
            "debridav.read-timeout-milliseconds=250",
            "debridav.retries-on-provider-error=1",
            "debridav.delay-between-retries=1ms"
        ]
)
@MockServerTest
class DebridProviderErrorHandlingIT {
    @Autowired
    lateinit var httpClient: HttpClient

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var premiumizeStubbingService: PremiumizeStubbingService

    @Autowired
    private lateinit var realDebridStubbingService: RealDebridStubbingService

    @Autowired
    private lateinit var realDebridClient: RealDebridClient

    @Autowired
    private lateinit var contentStubbingService: ContentStubbingService

    @Autowired
    lateinit var realDebridConfiguration: RealDebridConfiguration

    @Autowired
    lateinit var debridavConfiguration: DebridavConfiguration

    @Value("\${mockserver.port}")
    lateinit var port: String


    @AfterEach
    fun tearDown() {
        File(BASE_PATH).deleteRecursively()
        premiumizeStubbingService.reset()
    }

    @Test
    fun thatNetworkErrorProducesCachedFileOfTypeNetworkError() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")
        val failingRealDebridClient = RealDebridClient(
            RealDebridConfiguration("na", "localhost:1"),
            httpClient
        )
        (realDebridClient as RealDebridClientProxy).realDebridClient = failingRealDebridClient
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

        val fileContents: DebridFileContents = Json.decodeFromString(
            File("/tmp/debridavtests/downloads/test/a/b/c/movie.mkv.debridfile").readText()
        )

        kotlin.test.assertEquals(
            fileContents,
            DebridFileContents(
                originalPath = "a/b/c/movie.mkv",
                size = 100000000,
                modified = 0,
                magnet = "magnet:?xt=urn:btih:hash&dn=test&tr=",
                debridLinks = mutableListOf(
                    NetworkError(DebridProvider.REAL_DEBRID, 0),
                    CachedFile(
                        path = "a/b/c/movie.mkv",
                        size = 100000000,
                        mimeType = "video/mp4",
                        link = "http://localhost:$port/workingLink",
                        lastChecked = 0,
                        params = hashMapOf(),
                        provider = DebridProvider.PREMIUMIZE
                    )
                )
            )
        )

        // finally
        val workingRealDebridClient = RealDebridClient(
            realDebridConfiguration,
            httpClient
        )
        (realDebridClient as RealDebridClientProxy).realDebridClient = workingRealDebridClient
    }

    @Test
    fun thatProviderErrorProducesCachedFileOfTypeProviderError() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()
        realDebridStubbingService.mock503AddMagnetResponse()


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

        val fileContents: DebridFileContents = Json.decodeFromString(
            File("/tmp/debridavtests/downloads/test/a/b/c/movie.mkv.debridfile").readText()
        )

        kotlin.test.assertEquals(
            fileContents,
            DebridFileContents(
                originalPath = "a/b/c/movie.mkv",
                size = 100000000,
                modified = 0,
                magnet = "magnet:?xt=urn:btih:hash&dn=test&tr=",
                debridLinks = mutableListOf(
                    ProviderError(DebridProvider.REAL_DEBRID, 0),
                    CachedFile(
                        path = "a/b/c/movie.mkv",
                        size = 100000000,
                        mimeType = "video/mp4",
                        link = "http://localhost:$port/workingLink",
                        lastChecked = 0,
                        params = hashMapOf(),
                        provider = DebridProvider.PREMIUMIZE
                    )
                )
            )
        )
    }

    @Test
    fun that4xxResponseProducesCachedFileOfTypeClientError() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()
        realDebridStubbingService.mock400AddMagnetResponse()


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

        val fileContents: DebridFileContents = Json.decodeFromString(
            File("/tmp/debridavtests/downloads/test/a/b/c/movie.mkv.debridfile").readText()
        )

        kotlin.test.assertEquals(
            fileContents,
            DebridFileContents(
                originalPath = "a/b/c/movie.mkv",
                size = 100000000,
                modified = 0,
                magnet = "magnet:?xt=urn:btih:hash&dn=test&tr=",
                debridLinks = mutableListOf(
                    ClientError(DebridProvider.REAL_DEBRID, 0),
                    CachedFile(
                        path = "a/b/c/movie.mkv",
                        size = 100000000,
                        mimeType = "video/mp4",
                        link = "http://localhost:$port/workingLink",
                        lastChecked = 0,
                        params = hashMapOf(),
                        provider = DebridProvider.PREMIUMIZE
                    )
                )
            )
        )
    }

    @Test
    fun thatStaleFileGetsDeletedWhenSettingIsEnabled() {
        // given
        val file = File("$BASE_PATH/testfile.mp4.debridfile")
        if (file.exists()) {
            file.delete()
        }
        file.parentFile.mkdirs()
        file.createNewFile()
        debridavConfiguration.debridClients = listOf(DebridProvider.PREMIUMIZE)
        //every { debridavConfiguration.debridClients } returns listOf(DebridProvider.PREMIUMIZE)

        val staleDebridFileContents = debridFileContents.deepCopy()
        staleDebridFileContents.debridLinks = mutableListOf(
            staleDebridFileContents.debridLinks.first { it.provider == DebridProvider.PREMIUMIZE }.let {
                CachedFile(
                    path = "a/b/c/movie.mkv",
                    size = 100000000,
                    mimeType = "video/mp4",
                    link = "http://localhost:$port/deadLink",
                    lastChecked = 0,
                    params = hashMapOf(),
                    provider = it.provider
                )
            }
        )
        file.writeText(Json.encodeToString(staleDebridFileContents))

        premiumizeStubbingService.mockIsNotCached()
        contentStubbingService.mockDeadLink()

        // when
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .get()
            .uri("testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful

        assertFalse { file.exists() }
        debridavConfiguration.debridClients = listOf(DebridProvider.REAL_DEBRID, DebridProvider.PREMIUMIZE)
    }

    private fun DebridFileContents.deepCopy() =
        Json.decodeFromString<DebridFileContents>(
            Json.encodeToString(DebridFileContents.serializer(), this)
        )
}




