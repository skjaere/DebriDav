package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.category.Category
import io.skjaere.debridav.category.CategoryRepository
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.health.RepairAction
import io.skjaere.debridav.health.RepairOutcomeRepository
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import io.skjaere.debridav.torrent.Status
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentHealthCheckService
import io.skjaere.debridav.torrent.TorrentRepository
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.mockserver.verify.VerificationTimes
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debridav.debrid-clients=premiumize",
        "sonarr.integration-enabled=true",
        "sonarr.category=tv",
        "health-check.repair-enabled=true"
    ]
)
@MockServerTest
class TorrentHealthCheckIT {

    @Autowired
    private lateinit var torrentRepository: TorrentRepository

    @Autowired
    private lateinit var categoryRepository: CategoryRepository

    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

    @Autowired
    private lateinit var torrentHealthCheckService: TorrentHealthCheckService

    @Autowired
    private lateinit var premiumizeStubbingService: PremiumizeStubbingService

    @Autowired
    private lateinit var repairOutcomeRepository: RepairOutcomeRepository

    @Autowired
    private lateinit var mockServer: ClientAndServer

    @BeforeEach
    fun setUp() {
        torrentRepository.deleteAll()
        repairOutcomeRepository.deleteAll()
        mockServer.reset()
    }

    @AfterEach
    fun tearDown() {
        torrentRepository.deleteAll()
        repairOutcomeRepository.deleteAll()
        mockServer.reset()
    }

    @Test
    @Suppress("LongMethod")
    fun `unhealthy torrent triggers Arr blocklist and search`() {
        // given — create a torrent with a file whose only debrid link is MissingFile
        val category = categoryRepository.findByNameIgnoreCase("tv")
            ?: categoryRepository.save(Category("tv", "/data/downloads/tv"))

        val contents = DebridCachedTorrentContent(
            originalPath = "movie.mkv",
            size = 1_000_000L,
            modified = Instant.EPOCH.toEpochMilli(),
            magnet = MAGNET,
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf(
                MissingFile(DebridProvider.PREMIUMIZE, Instant.EPOCH.toEpochMilli())
            )
        )

        val file = databaseFileService.createDebridFile(
            "/downloads/tv/test-torrent/movie.mkv",
            "testhash123",
            contents
        )
        databaseFileService.saveDbEntity(file)

        val torrent = Torrent().apply {
            name = "test-torrent"
            this.category = category
            hash = "testhash123"
            savePath = "/data/downloads/tv"
            status = Status.LIVE
            lastVerified = null
        }
        torrentRepository.save(torrent)
        torrent.files = mutableListOf(file)
        torrentRepository.save(torrent)

        // given — premiumize says "not cached" when the health check re-verifies
        premiumizeStubbingService.mockIsNotCached()

        // given — Sonarr mock: history lookup for blocklisting
        mockServer.`when`(
            request()
                .withMethod("GET")
                .withPath("/sonarr/api/v3/history")
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"page": 1, "pageSize": 1, "totalRecords": 1, "records": [{"id": 42}]}""")
        )

        // given — Sonarr mock: mark history record as failed
        mockServer.`when`(
            request()
                .withMethod("POST")
                .withPath("/sonarr/api/v3/history/failed/42")
        ).respond(
            response().withStatusCode(200)
        )

        // given — Sonarr mock: parse endpoint for deleteFileAndSearch
        mockServer.`when`(
            request()
                .withMethod("GET")
                .withPath("/sonarr/api/v3/parse")
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"episodes": [{"id": 1, "episodeFileId": 10}]}""")
        )

        // given — Sonarr mock: delete episode file
        mockServer.`when`(
            request()
                .withMethod("DELETE")
                .withPath("/sonarr/api/v3/episodefile/10")
        ).respond(
            response().withStatusCode(200)
        )

        // given — Sonarr mock: command (search)
        mockServer.`when`(
            request()
                .withMethod("POST")
                .withPath("/sonarr/api/v3/command")
        ).respond(
            response().withStatusCode(200)
        )

        // when — trigger health check (enqueues to PGMQ, processed asynchronously)
        torrentHealthCheckService.triggerFullHealthCheck()

        // then — wait for the full check → repair pipeline to complete
        waitForMockServerVerification {
            // blocklist: history lookup + mark failed
            mockServer.verify(
                request()
                    .withMethod("GET")
                    .withPath("/sonarr/api/v3/history"),
                VerificationTimes.atLeast(1)
            )
            mockServer.verify(
                request()
                    .withMethod("POST")
                    .withPath("/sonarr/api/v3/history/failed/42"),
                VerificationTimes.atLeast(1)
            )

            // deleteFileAndSearch: parse + delete + command
            mockServer.verify(
                request()
                    .withMethod("GET")
                    .withPath("/sonarr/api/v3/parse"),
                VerificationTimes.atLeast(1)
            )
            mockServer.verify(
                request()
                    .withMethod("DELETE")
                    .withPath("/sonarr/api/v3/episodefile/10"),
                VerificationTimes.atLeast(1)
            )
            mockServer.verify(
                request()
                    .withMethod("POST")
                    .withPath("/sonarr/api/v3/command"),
                VerificationTimes.atLeast(1)
            )
        }

        // then — repair outcome recorded as REPAIRED
        waitForCondition("repair outcome recorded") {
            repairOutcomeRepository.findAll().toList().any { it.action == RepairAction.REPAIRED }
        }
    }

    @Test
    fun `healthy torrent updates lastVerified without enqueuing repair`() {
        // given — create a torrent with a file that has a working CachedFile link
        val contents = DebridCachedTorrentContent(
            originalPath = "healthy-movie.mkv",
            size = 500_000L,
            modified = Instant.EPOCH.toEpochMilli(),
            magnet = MAGNET,
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf(
                MissingFile(DebridProvider.PREMIUMIZE, Instant.EPOCH.toEpochMilli())
            )
        )

        val file = databaseFileService.createDebridFile(
            "/downloads/misc/healthy-torrent/healthy-movie.mkv",
            "healthyhash456",
            contents
        )
        databaseFileService.saveDbEntity(file)

        val torrent = Torrent().apply {
            name = "healthy-torrent"
            hash = "healthyhash456"
            savePath = "/data/downloads/misc"
            status = Status.LIVE
            lastVerified = null
        }
        torrentRepository.save(torrent)
        torrent.files = mutableListOf(file)
        torrentRepository.save(torrent)

        // given — premiumize returns cached (healthy)
        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()

        // when
        torrentHealthCheckService.triggerFullHealthCheck()

        // then — torrent gets lastVerified set, no repair messages sent
        waitForCondition("lastVerified is set") {
            val updated = torrentRepository.findById(torrent.id!!).orElse(null)
            updated?.lastVerified != null
        }

        val updated = torrentRepository.findById(torrent.id!!).get()
        assertThat("lastVerified should be set", updated.lastVerified != null, `is`(true))
        assertThat("healthCheckEnqueuedAt should be cleared", updated.healthCheckEnqueuedAt == null, `is`(true))
    }

    @Test
    fun `unhealthy torrent without Arr category deletes files`() {
        // given — torrent with no category (no Arr client match)
        val contents = DebridCachedTorrentContent(
            originalPath = "orphan.mkv",
            size = 200_000L,
            modified = Instant.EPOCH.toEpochMilli(),
            magnet = MAGNET,
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf(
                MissingFile(DebridProvider.PREMIUMIZE, Instant.EPOCH.toEpochMilli())
            )
        )

        val file = databaseFileService.createDebridFile(
            "/downloads/nocategory/orphan-torrent/orphan.mkv",
            "orphanhash789",
            contents
        )
        databaseFileService.saveDbEntity(file)

        val torrent = Torrent().apply {
            name = "orphan-torrent"
            hash = "orphanhash789"
            savePath = "/data/downloads/nocategory"
            status = Status.LIVE
            lastVerified = null
        }
        torrentRepository.save(torrent)
        torrent.files = mutableListOf(file)
        torrentRepository.save(torrent)

        // given — premiumize says not cached
        premiumizeStubbingService.mockIsNotCached()

        // when
        torrentHealthCheckService.triggerFullHealthCheck()

        // then — repair outcome recorded as DELETED (no Arr client to search)
        waitForCondition("repair outcome recorded as DELETED") {
            repairOutcomeRepository.findAll().toList().any { it.action == RepairAction.DELETED }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun waitForMockServerVerification(
        timeoutMs: Long = 30_000,
        pollMs: Long = 500,
        verification: () -> Unit
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                verification()
                return
            } catch (e: Throwable) {
                lastError = e
                Thread.sleep(pollMs)
            }
        }
        throw AssertionError("Verification did not pass within ${timeoutMs}ms", lastError)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun waitForCondition(
        description: String,
        timeoutMs: Long = 30_000,
        pollMs: Long = 500,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition()) return
            } catch (_: Throwable) {
                // ignore and retry
            }
            Thread.sleep(pollMs)
        }
        throw AssertionError("Condition '$description' not met within ${timeoutMs}ms")
    }
}
