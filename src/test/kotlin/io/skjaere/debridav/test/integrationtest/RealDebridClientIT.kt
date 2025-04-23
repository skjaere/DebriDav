package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridDownloadRepository
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentRepository
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.deepCopy
import io.skjaere.debridav.test.integrationtest.config.ContentStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.RealDebridStubbingService
import io.skjaere.debridav.test.realDebridCachedFile
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.awaitility.Awaitility.await
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ResourceLoader
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant
import java.util.Map

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=real_debrid"]
)
@MockServerTest
class RealDebridClientIT {
    @Autowired
    private lateinit var contentStubbingService: ContentStubbingService

    @Autowired
    lateinit var databaseFileService: DatabaseFileService

    @Autowired
    lateinit var debridFileContentsRepository: DebridFileContentsRepository

    @Autowired
    lateinit var realdebridTorrentRepository: RealDebridTorrentRepository

    @Autowired
    lateinit var realDebridDownloadRepository: RealDebridDownloadRepository

    @Autowired
    lateinit var realDebridStubbingService: RealDebridStubbingService

    @Autowired
    lateinit var realDebridTorrentRepository: RealDebridTorrentRepository

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var resourceLoader: ResourceLoader

    @Autowired
    lateinit var mockserverClient: MockServerClient

    @LocalServerPort
    var randomServerPort: Int = 0
    private val sardine = SardineFactory.begin()

    @AfterEach
    fun tearDown() {
        mockserverClient.reset()
        realDebridDownloadRepository.deleteAll()
        realDebridTorrentRepository.deleteAll()
    }

    @Test
    fun `that existing torrent and existing download get reused`() {
        // given
        realDebridStubbingService.stubTorrentsListResponse()
        realDebridStubbingService.stubMultipleDownloadsResponse()
        realDebridStubbingService.stubTorrentInfoResponse(
            "LD3PPDP4R4LAY",
            resourceLoader.getResource("classpath:real_debrid_stubs/specific_torrent_info.json")
                .getContentAsString(Charset.defaultCharset())
        )
        enableTorrentImport()
        await().until { realDebridTorrentRepository.findAll().count() == 3 }
        val parts = MultipartBodyBuilder()
        parts.part(
            "urls",
            "magnet:?xt=urn:btih:6638e282767b7c710ff561a5cfd4f7e4ceb5d448" +
                    "&dn=Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE&tr="
        )
        parts.part("category", "test")
        parts.part("paused", "false")

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
        assertThat(
            sardine.list(
                "http://localhost:${randomServerPort}/downloads/" +
                        "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE/Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE"
            ),
            hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv")
                )
            )
        )

        //finally
        realdebridTorrentRepository.deleteAll()
        realDebridDownloadRepository.deleteAll()
        sardine.delete(
            "http://localhost:${randomServerPort}/downloads/" +
                    "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE"
        )
    }

    @Test
    @Suppress("LongMethod")
    fun `that existing torrent is re-used, and new download is added`() {
        // given
        realDebridStubbingService.stubTorrentsListResponse()
        realDebridStubbingService.stubMultipleDownloadsResponse()
        realDebridStubbingService.stubUnrestrictLink(
            "https://real-debrid.com/d/7AULFT3RUWGL2CB2",
            resourceLoader.getResource("classpath:real_debrid_stubs/unrestrict_link_response.json")
                .getContentAsString(Charset.defaultCharset())
        )
        realDebridStubbingService.stubTorrentInfoResponse(
            "LD3PPDP4R4LAY",
            resourceLoader.getResource("classpath:real_debrid_stubs/specific_torrent_info.json")
                .getContentAsString(Charset.defaultCharset())
        )
        enableTorrentImport()
        await().until { realDebridTorrentRepository.findAll().count() == 3 }
        realDebridDownloadRepository.deleteAll()
        val parts = MultipartBodyBuilder()
        parts.part(
            "urls",
            "magnet:?xt=urn:btih:6638e282767b7c710ff561a5cfd4f7e4ceb5d448" +
                    "&dn=Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE&tr="
        )
        parts.part("category", "test")
        parts.part("paused", "false")
        assert(realDebridDownloadRepository.findAll().toList().isEmpty())

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
        assertThat(
            sardine.list(
                "http://localhost:${randomServerPort}/downloads/" +
                        "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE/Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE"
            ),
            hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv")
                )
            )
        )

        assertThat(
            sardine.list(
                "http://localhost:${randomServerPort}/downloads/" +
                        "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE/Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE"
            ),
            hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv")
                )
            )
        )
        assertNotNull(realDebridDownloadRepository.getByDownloadIdIgnoreCase("7AULFT3RUWGL2CB2"))

        //finally
        realdebridTorrentRepository.deleteAll()
        realDebridDownloadRepository.deleteAll()
        sardine.delete("http://localhost:${randomServerPort}/downloads/Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE")
    }

    @Test
    fun `that new torrent and download works`() {
        // given
        val magnet =
            "magnet:?xt=urn:btih:6638e282767b7c710ff561a5cfd4f7e4ceb5d44" +
                    "8&dn=Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE&tr="
        realDebridStubbingService.stubEmptyDownloadsResponse()
        realDebridStubbingService.stubEmptyTorrentsListResponse()
        realDebridStubbingService.stubAddMagnet(
            magnet,
            resourceLoader.getResource("classpath:real_debrid_stubs/add_magnet_response.json")
                .getContentAsString(Charset.defaultCharset())
        )
        realDebridStubbingService.stubUnrestrictLink(
            "https://real-debrid.com/d/7AULFT3RUWGL2CB2",
            resourceLoader.getResource("classpath:real_debrid_stubs/unrestrict_link_response.json")
                .getContentAsString(Charset.defaultCharset())
        )
        realDebridStubbingService.stubTorrentInfoResponse(
            "F36NGHRDO5CZM",
            resourceLoader.getResource("classpath:real_debrid_stubs/specific_torrent_info.json")
                .getContentAsString(Charset.defaultCharset())
        )
        realDebridDownloadRepository.deleteAll()
        val parts = MultipartBodyBuilder()
        parts.part(
            "urls",
            magnet
        )
        parts.part("category", "test")
        parts.part("paused", "false")

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

        //then
        assertThat(
            sardine.list(
                "http://localhost:${randomServerPort}/downloads/" +
                        "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE/Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE"
            ),
            hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv")
                )
            )
        )
        assertNotNull(realDebridDownloadRepository.getByDownloadIdIgnoreCase("7AULFT3RUWGL2CB2"))
        assertNotNull(realdebridTorrentRepository.getByTorrentIdIgnoreCase("F36NGHRDO5CZM"))

        //finally
        realdebridTorrentRepository.deleteAll()
        realDebridDownloadRepository.deleteAll()
        sardine.delete(
            "http://localhost:${randomServerPort}/downloads/" +
                    "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE"
        )
    }

    @Test
    fun `that refreshing stale link deletes stale download`() {
        //given
        val staleLinkId = "7AULFT3RUWGL2CB2"
        val freshLinkId = "44DFOMVTFGLTE"
        val torrentId = "LD3PPDP4R4LAY"
        val filesize = 3787132621

        realDebridStubbingService.stubUnrestrictLink(
            "https://real-debrid.com/d/$staleLinkId",
            resourceLoader.getResource("classpath:real_debrid_stubs/unrestrict_link_response.json")
                .getContentAsString(Charset.defaultCharset())
                .replace("%DOWNLOAD_LINK%", "http://localhost:${mockserverClient.port}/workingLink")
                .replace(staleLinkId, freshLinkId)
        )
        val nonWorkingDownload = resourceLoader.getResource("classpath:real_debrid_stubs/unrestrict_link_response.json")
            .getContentAsString(Charset.defaultCharset())
            .replace("%DOWNLOAD_LINK%", "http://localhost:${mockserverClient.port}/nonWorkingLink")

            .replace("1373366001", filesize.toString())

        realDebridStubbingService.stubDownloadsResponse("[$nonWorkingDownload]")
        realDebridStubbingService.stubSingleTorrentsListResponse(torrentId, staleLinkId)
        realDebridStubbingService.stubTorrentInfoResponse(
            torrentId, resourceLoader.getResource("classpath:real_debrid_stubs/specific_torrent_info.json")
                .getContentAsString(Charset.defaultCharset())
        )
        enableTorrentImport()
        contentStubbingService.mockWorkingStream("/workingLink")
        val realdebridCachedFileWithNonWorkingLink = CachedFile(
            "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv",
            filesize,
            realDebridCachedFile.mimeType!!,
            "http://localhost:$randomServerPort/nonWorkingLink",
            mapOf("link" to "https://real-debrid.com/d/$freshLinkId"),
            Instant.now().toEpochMilli(),
            DebridProvider.REAL_DEBRID
        )
        val debridFileContents = debridFileContents.deepCopy()
        debridFileContents.size = "it works!".toByteArray().size.toLong()
        debridFileContents.debridLinks = mutableListOf(realdebridCachedFileWithNonWorkingLink)
        databaseFileService.createDebridFile("/downloads/testfile.mp4", "hash", debridFileContents)
            .let { debridFileContentsRepository.save(it) }

        //when
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(3000000))
            .build()
            .get()
            .uri("/downloads/testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful()
            .expectBody().equals("it works!")

        //then
        assertNull(realDebridDownloadRepository.getByDownloadIdIgnoreCase(staleLinkId))
        assertNotNull(realDebridDownloadRepository.getByDownloadIdIgnoreCase(freshLinkId))

        //finally
        sardine.delete(
            "http://localhost:${randomServerPort}/downloads/testfile.mp4"
        )
    }

    private fun enableTorrentImport() {
        webTestClient
            .post()
            .uri("/actuator/realdebrid")
            .header("Content-Type", "application/json")
            .bodyValue(Map.of<String?, String?>("torrentImportEnabled", "true"))
            .exchange()
            .expectStatus().is2xxSuccessful()
    }
}
