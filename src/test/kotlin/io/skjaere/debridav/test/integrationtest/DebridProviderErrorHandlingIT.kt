package io.skjaere.debridav.test.integrationtest

import com.github.sardine.SardineFactory
import io.ktor.client.HttpClient
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.category.CategoryService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfiguration
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.ClientError
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.NetworkError
import io.skjaere.debridav.fs.ProviderError
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.deepCopy
import io.skjaere.debridav.test.integrationtest.config.ContentStubbingService
import io.skjaere.debridav.test.integrationtest.config.EasynewsStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import io.skjaere.debridav.test.integrationtest.config.RealDebridClientProxy
import io.skjaere.debridav.test.integrationtest.config.RealDebridStubbingService
import io.skjaere.debridav.test.usenetDebridFileContents
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentRepository
import io.skjaere.debridav.usenet.UsenetDownload
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.apache.commons.codec.digest.DigestUtils
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.samePropertyValuesAs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Duration

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
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
    private lateinit var usenetRepository: UsenetRepository

    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

    @Autowired
    lateinit var httpClient: HttpClient

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var premiumizeStubbingService: PremiumizeStubbingService

    @Autowired
    private lateinit var realDebridStubbingService: RealDebridStubbingService

    @Autowired
    private lateinit var easynewsStubbingService: EasynewsStubbingService

    @Autowired
    private lateinit var realDebridClient: RealDebridClient

    @Autowired
    private lateinit var contentStubbingService: ContentStubbingService

    @Autowired
    lateinit var debridavConfigurationProperties: DebridavConfigurationProperties

    @Autowired
    lateinit var torrentRepository: TorrentRepository

    @Autowired
    lateinit var categoryService: CategoryService

    @Autowired
    lateinit var debridFileContentsRepository: DebridFileContentsRepository

    @Autowired
    lateinit var mockserverClient: ClientAndServer

    @LocalServerPort
    var randomServerPort: Int = 0


    @Value("\${mockserver.port}")
    lateinit var port: String

    private val hash = DigestUtils.md5Hex("test")

    private val sardine = SardineFactory.begin()


    @AfterEach
    fun tearDown() {
        try {
            sardine.delete("http://localhost:${randomServerPort}/downloads/test")
        } catch (_: Exception) {
        }
        mockserverClient.reset()
    }

    @Test
    @Suppress("LongMethod")
    @Disabled
    fun thatNetworkErrorProducesCachedFileOfTypeNetworkError() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")

        val failingRealDebridClient = RealDebridClient(
            RealDebridConfiguration("na", "localhost:1"),
            httpClient,
            debridavConfigurationProperties
        )
        (realDebridClient as RealDebridClientProxy).realDebridClient = failingRealDebridClient
        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()


        // when
        webTestClient
            .mutate().responseTimeout(Duration.ofMillis(30000)).build()
            .post().uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        val fileContents = databaseFileService.getFileAtPath("/downloads/test/a/b/c/movie.mkv") as RemotelyCachedEntity

        assertThat(
            fileContents.contents, samePropertyValuesAs(
                DebridCachedTorrentContent(
                    originalPath = "a/b/c/movie.mkv",
                    size = 100000000,
                    modified = 0,
                    magnet = "magnet:?xt=urn:btih:hash&dn=test&tr=",
                    mimeType = "video/mp4",
                    debridLinks = mutableListOf()
                ), "debridLinks", "id"
            )
        )
        assertThat(
            fileContents.contents?.debridLinks,
            containsInAnyOrder(
                samePropertyValuesAs(
                    CachedFile(
                        path = "a/b/c/movie.mkv",
                        size = 100000000,
                        mimeType = "video/mp4",
                        link = "http://localhost:$port/workingLink",
                        lastChecked = 0,
                        params = hashMapOf(),
                        provider = DebridProvider.PREMIUMIZE,
                    ), "id"
                ),
                samePropertyValuesAs(NetworkError(DebridProvider.REAL_DEBRID, 0), "id"),
            )
        )

        /* // finally
         runBlocking {
             databaseFileService.getFileAtPath("/downloads/test/a/b/c/movie.mkv")?.let { file ->
                 databaseFileService.deleteFile(file)
             }
         }*/
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
        webTestClient.mutate().responseTimeout(Duration.ofMillis(30000)).build()
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        val fileContents = databaseFileService.getFileAtPath("/downloads/test/a/b/c/movie.mkv") as RemotelyCachedEntity

        assertThat(
            fileContents.contents, samePropertyValuesAs(
                DebridCachedTorrentContent(
                    originalPath = "a/b/c/movie.mkv",
                    size = 100000000,
                    modified = 0,
                    magnet = "magnet:?xt=urn:btih:hash&dn=test&tr=",
                    mimeType = "video/mp4",
                    debridLinks = mutableListOf()
                ), "debridLinks", "id"
            )
        )
        assertThat(
            fileContents.contents?.debridLinks,
            containsInAnyOrder(
                samePropertyValuesAs(
                    CachedFile(
                        path = "a/b/c/movie.mkv",
                        size = 100000000,
                        mimeType = "video/mp4",
                        link = "http://localhost:$port/workingLink",
                        lastChecked = 0,
                        params = hashMapOf(),
                        provider = DebridProvider.PREMIUMIZE
                    ), "id"
                ),
                samePropertyValuesAs(
                    ProviderError(DebridProvider.REAL_DEBRID, 0), "id"
                ),

                )
        )
        /*
                //finally
                sardine.delete("http://localhost:$randomServerPort/downloads/test")*/
    }

    @Test
    @Suppress("LongMethod")
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
            .mutate().responseTimeout(Duration.ofMillis(30000)).build()
            .post().uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
        val fileContents = databaseFileService.getFileAtPath("/downloads/test/a/b/c/movie.mkv") as RemotelyCachedEntity

        assertThat(
            fileContents.contents, samePropertyValuesAs(
                DebridCachedTorrentContent(
                    originalPath = "a/b/c/movie.mkv",
                    size = 100000000,
                    modified = 0,
                    magnet = "magnet:?xt=urn:btih:hash&dn=test&tr=",
                    mimeType = "video/mp4",
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
                ), "debridLinks", "id"
            )
        )
        assertThat(
            fileContents.contents?.debridLinks,
            hasItems(
                samePropertyValuesAs(
                    CachedFile(
                        path = "a/b/c/movie.mkv",
                        size = 100000000,
                        mimeType = "video/mp4",
                        link = "http://localhost:$port/workingLink",
                        lastChecked = 0,
                        params = hashMapOf(),
                        provider = DebridProvider.PREMIUMIZE
                    ), "id"
                ),
                samePropertyValuesAs(ClientError(DebridProvider.REAL_DEBRID, 0), "id"),
            )
        )
    }

    @Test
    fun `that stale torrent file gets deleted when setting is enabled`() {
        // given
        debridavConfigurationProperties.debridClients = listOf(DebridProvider.PREMIUMIZE)

        val staleDebridFileContents = debridFileContents.deepCopy()
        staleDebridFileContents.debridLinks = mutableListOf(
            CachedFile(
                path = "a/b/c/movie.mkv",
                size = 100000000,
                mimeType = "video/mp4",
                link = "http://localhost:$port/deadLink",
                lastChecked = 0,
                params = hashMapOf(),
                provider = staleDebridFileContents.debridLinks
                    .first { it.provider == DebridProvider.PREMIUMIZE }
                    .provider!!
            )
        )
        val dbEntity = databaseFileService.createDebridFile("/testfile.mp4", hash, staleDebridFileContents)
        val category = runBlocking {
            categoryService.findByName("test") ?: categoryService.createCategory("test")
        }
        val torrent = Torrent()
        torrent.name = "test"
        torrent.category = category
        torrent.files = mutableListOf(dbEntity)
        torrent.hash = hash
        torrent.savePath = "/downloads/test"
        runBlocking {
            torrentRepository.save(torrent)
        }

        premiumizeStubbingService.mockIsNotCached()
        contentStubbingService.mockDeadLink()

        // when
        webTestClient
            .mutate().responseTimeout(Duration.ofMillis(30000)).build()
            .get()
            .uri("testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        assertNull(databaseFileService.getFileAtPath("/testfile.mp4"))
        debridavConfigurationProperties.debridClients = listOf(DebridProvider.REAL_DEBRID, DebridProvider.PREMIUMIZE)
    }

    @Test
    fun `that stale usenet file gets deleted when setting is enabled`() {
        // given
        debridavConfigurationProperties.debridClients = listOf(DebridProvider.PREMIUMIZE)

        val staleDebridFileContents = usenetDebridFileContents.deepCopy()
        staleDebridFileContents.debridLinks = mutableListOf(
            CachedFile(
                path = "testfile.mkv",
                size = 100000000,
                mimeType = "video/mp4",
                link = "http://localhost:$port/deadLink",
                lastChecked = 0,
                params = hashMapOf(),
                provider = DebridProvider.EASYNEWS
            )
        )
        val dbEntity = databaseFileService.createDebridFile("/testfile.mp4", hash, staleDebridFileContents)
        val usenetDownload = UsenetDownload()
        usenetDownload.name = "test"
        usenetDownload.category = runBlocking {
            categoryService.findByName("test") ?: categoryService.createCategory("test")
        }
        usenetDownload.debridFiles = mutableListOf(dbEntity)
        usenetDownload.hash = hash
        runBlocking {
            usenetRepository.save(usenetDownload)
        }

        easynewsStubbingService.mockIsNotCached()
        contentStubbingService.mockDeadLink()

        // when
        webTestClient
            .mutate().responseTimeout(Duration.ofMillis(30000)).build()
            .get()
            .uri("testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        assertNull(databaseFileService.getFileAtPath("/testfile.mp4"))
        debridavConfigurationProperties.debridClients = listOf(DebridProvider.REAL_DEBRID, DebridProvider.PREMIUMIZE)
    }
}




