package io.skjaere.debridav.test.integrationtest

import com.github.sardine.SardineFactory
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridDownloadRepository
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentRepository
import io.skjaere.debridav.health.PgmqHealthQueueRepository
import io.skjaere.debridav.health.RepairOutcomeRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.RealDebridStubbingService
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentHealthCheckService
import io.skjaere.debridav.torrent.TorrentRepository
import kotlin.test.fail
import org.awaitility.Awaitility.await
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse
import org.mockserver.model.HttpResponse.response
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

private const val HASH = "6638e282767b7c710ff561a5cfd4f7e4ceb5d448"
private const val TORRENT_NAME = "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE"
private const val MAGNET = "magnet:?xt=urn:btih:$HASH&dn=$TORRENT_NAME&tr="
private const val TORRENT_ID = "F36NGHRDO5CZM"
private const val LINK = "https://real-debrid.com/d/7AULFT3RUWGL2CB2"

/**
 * Health checks of torrents grabbed through Real-Debrid. RD has no cache-check endpoint, so the
 * check answers from the local mirror of the user's RD library (`real_debrid_torrent_entity`),
 * which the `/torrents/` sync refreshes every `real-debrid.sync-poll-rate`. Sync is disabled in
 * the test profile, so these tests cover mirror rows that no sync has refreshed: the state of
 * every new grab until the next sync, of every row V26 added `status` to, and of every row on an
 * install with `real-debrid.sync-enabled=false`.
 */
@SpringBootTest(
    classes = [
        DebriDavApplication::class,
        IntegrationTestContextConfiguration::class,
        MiltonConfiguration::class,
        TestSecurityConfiguration::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=real_debrid"]
)
@MockServerTest
class RealDebridHealthCheckIT {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var mockserverClient: MockServerClient

    @Autowired
    lateinit var resourceLoader: ResourceLoader

    @Autowired
    lateinit var realDebridStubbingService: RealDebridStubbingService

    @Autowired
    lateinit var realDebridTorrentRepository: RealDebridTorrentRepository

    @Autowired
    lateinit var realDebridDownloadRepository: RealDebridDownloadRepository

    @Autowired
    lateinit var torrentRepository: TorrentRepository

    @Autowired
    lateinit var torrentHealthCheckService: TorrentHealthCheckService

    @Autowired
    lateinit var pgmqHealthQueueRepository: PgmqHealthQueueRepository

    @Autowired
    lateinit var repairOutcomeRepository: RepairOutcomeRepository

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    @BeforeEach
    fun setUp() = cleanUp()

    @AfterEach
    fun tearDown() = cleanUp()

    @Test
    fun `a fresh Real-Debrid grab is not due for a health check straight away`() {
        val torrent = grabViaQbittorrentApi()

        assertThat("lastVerified of a fresh grab", torrent.lastVerified, notNullValue())
    }

    @Test
    fun `a Real-Debrid grab passes its health check once its files are selected`() {
        //given
        val torrent = grabViaQbittorrentApi()

        //when
        runHealthCheck(torrent)

        //then
        assertNoRepairFor(torrent)
        assertThat(realDebridTorrentRepository.getByTorrentIdIgnoreCase(TORRENT_ID)?.status, `is`("downloaded"))
    }

    @Test
    fun `a mirror row without a status is verified with Real-Debrid instead of repaired`() {
        //given: a mirror row as V26 left every existing row, with `status` NULL until the next sync
        val torrent = grabViaQbittorrentApi()
        setMirrorStatus(null)

        //when
        runHealthCheck(torrent)

        //then
        assertNoRepairFor(torrent)
        assertThat(realDebridTorrentRepository.getByTorrentIdIgnoreCase(TORRENT_ID)?.status, `is`("downloaded"))
    }

    @Test
    fun `a mirror row for a torrent Real-Debrid no longer has is still repaired`() {
        //given
        val torrent = grabViaQbittorrentApi()
        setMirrorStatus(null)
        mockserverClient.clear(torrentInfoRequest())
        mockserverClient.`when`(torrentInfoRequest()).respond(
            jsonResponse("""{"error": "unknown_ressource", "error_code": 7}""").withStatusCode(404)
        )

        //when
        runHealthCheck(torrent)

        //then
        await().atMost(Duration.ofSeconds(30)).until { repairOutcomeRepository.findAll().any() }
    }

    /** Adds [MAGNET] the way Sonarr/Radarr do, through the qBittorrent emulation API. */
    private fun grabViaQbittorrentApi(): Torrent {
        stubRealDebridAddFlow()
        val parts = MultipartBodyBuilder().apply {
            part("urls", MAGNET)
            part("category", "test")
            part("paused", "false")
        }
        webTestClient
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
        return torrentRepository.getByHashIgnoreCase(HASH) ?: fail("torrent $HASH was not created")
    }

    /**
     * Stubs RD the way it answers the add flow. Until files are selected the torrent is
     * `waiting_files_selection` with no links, and the add path reads `/torrents/info` twice
     * before selecting (once to mirror the new torrent, once to list its files). Afterwards it is
     * `downloaded`, with one link per selected file.
     */
    private fun stubRealDebridAddFlow() {
        realDebridStubbingService.stubEmptyDownloadsResponse()
        realDebridStubbingService.stubEmptyTorrentsListResponse()
        realDebridStubbingService.stubAddMagnet(MAGNET, fixture("add_magnet_response.json"))
        realDebridStubbingService.stubUnrestrictLink(LINK, fixture("unrestrict_link_response.json"))

        val selectedInfo = fixture("specific_torrent_info.json")
        val unselectedInfo = selectedInfo
            .replace("\"status\": \"downloaded\"", "\"status\": \"waiting_files_selection\"")
            .replace("\"selected\": 1", "\"selected\": 0")
            .replace(Regex("\"links\": \\[[^]]*]"), "\"links\": []")
        check("waiting_files_selection" in unselectedInfo && "\"links\": []" in unselectedInfo) {
            "specific_torrent_info.json no longer has the shape this stub rewrites"
        }
        mockserverClient.`when`(torrentInfoRequest(), Times.exactly(2)).respond(jsonResponse(unselectedInfo))
        mockserverClient.`when`(torrentInfoRequest()).respond(jsonResponse(selectedInfo))
        mockserverClient.`when`(
            request().withMethod("POST").withPath("/realdebrid/torrents/selectFiles/$TORRENT_ID")
        ).respond(response().withStatusCode(204))
    }

    /** Makes [torrent] due, as it is once `health-check.torrent-interval` has passed, and runs a sweep. */
    private fun runHealthCheck(torrent: Torrent) {
        torrentRepository.findById(torrent.id!!).get().let {
            it.lastVerified = null
            torrentRepository.save(it)
        }
        torrentHealthCheckService.checkTorrentHealth()
        // The check handler sets lastVerified once a check completes, healthy or not.
        await().atMost(Duration.ofSeconds(30)).until {
            torrentRepository.findById(torrent.id!!).map { it.lastVerified != null }.orElse(false)
        }
    }

    private fun assertNoRepairFor(torrent: Torrent) {
        // A check that finds the torrent missing enqueues the repair before it sets lastVerified,
        // and the repair stays in the queue until its outcome is recorded.
        assertThat(
            "repair queued for torrent ${torrent.id}",
            pgmqHealthQueueRepository.isRepairQueued(torrent.id!!),
            `is`(false)
        )
        assertThat("repair outcomes", repairOutcomeRepository.findAll().toList(), empty())
    }

    private fun setMirrorStatus(status: String?) {
        val row = realDebridTorrentRepository.getByTorrentIdIgnoreCase(TORRENT_ID)
            ?: fail("no mirror row for $TORRENT_ID")
        row.status = status
        realDebridTorrentRepository.save(row)
    }

    private fun torrentInfoRequest(): HttpRequest =
        request().withMethod("GET").withPath("/realdebrid/torrents/info/$TORRENT_ID")

    private fun jsonResponse(body: String): HttpResponse =
        response()
            .withStatusCode(200)
            .withContentType(org.mockserver.model.MediaType.APPLICATION_JSON)
            .withBody(body)

    private fun fixture(name: String): String =
        resourceLoader.getResource("classpath:real_debrid_stubs/$name").getContentAsString(Charset.defaultCharset())

    private fun cleanUp() {
        runCatching { sardine.delete("http://localhost:$randomServerPort/webdav/downloads/$TORRENT_NAME") }
        mockserverClient.reset()
        torrentRepository.deleteAll()
        repairOutcomeRepository.deleteAll()
        realDebridDownloadRepository.deleteAll()
        realDebridTorrentRepository.deleteAll()
    }
}
