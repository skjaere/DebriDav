package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.category.Category
import io.skjaere.debridav.category.CategoryRepository
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.premiumize.PremiumizeClient
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.health.HealthCheckConfigurationProperties
import io.skjaere.debridav.health.PgmqHealthQueueRepository
import io.skjaere.debridav.health.RepairAction
import io.skjaere.debridav.health.RepairOutcome
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
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.mockserver.verify.VerificationTimes
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
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

    @Autowired
    private lateinit var premiumizeClient: PremiumizeClient

    @Autowired
    private lateinit var pgmqHealthQueueRepository: PgmqHealthQueueRepository

    @Autowired
    private lateinit var healthCheckConfigurationProperties: HealthCheckConfigurationProperties

    @BeforeEach
    fun setUp() {
        healthCheckConfigurationProperties.maxRepairsPerHour = 0
        torrentRepository.deleteAll()
        repairOutcomeRepository.deleteAll()
        mockServer.reset()
        premiumizeClient.reset()
    }

    @AfterEach
    fun tearDown() {
        healthCheckConfigurationProperties.maxRepairsPerHour = 0
        torrentRepository.deleteAll()
        repairOutcomeRepository.deleteAll()
        mockServer.reset()
        premiumizeClient.reset()
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
            magnet = MAGNET
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

        // given — Sonarr mock: episode-file lookup. The client follows up on each
        // episode that has episodeFileId>0 to read the actual path before matching
        // — return the path that matches the file we just created in debridav so
        // the matcher accepts and the test proceeds into delete+search.
        mockServer.`when`(
            request()
                .withMethod("GET")
                .withPath("/sonarr/api/v3/episodefile/10")
        ).respond(
            response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"id": 10, "path": "/data/downloads/tv/test-torrent/movie.mkv"}""")
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
            magnet = MAGNET
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
            magnet = MAGNET
            savePath = "/data/downloads/nocategory"
            status = Status.LIVE
            lastVerified = null
        }
        torrentRepository.save(torrent)
        torrent.files = mutableListOf(file)
        torrentRepository.save(torrent)

        // given — premiumize says not cached
        premiumizeStubbingService.mockIsNotCached()
        // given — the repair fans out to every arr, and neither Sonarr nor Radarr recognises the release
        mockServer.`when`(request().withMethod("GET").withPath("/sonarr/api/v3/parse")).respond(
            response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"episodes": []}""")
        )
        mockServer.`when`(request().withMethod("GET").withPath("/radarr/api/v3/parse")).respond(
            response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"movie": null}""")
        )

        // when
        torrentHealthCheckService.triggerFullHealthCheck()

        // then — repair outcome recorded as DELETED (no arr has it imported) and the file is gone
        waitForCondition("repair outcome recorded as DELETED") {
            repairOutcomeRepository.findAll().toList().any { it.action == RepairAction.DELETED }
        }
        assertThat(
            "file still in debridav",
            databaseFileService.getFileAtPath("/downloads/nocategory/orphan-torrent/orphan.mkv") != null,
            `is`(false)
        )
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = [429, 503])
    fun `a provider error during the check does not repair the torrent`(statusCode: Int) {
        //given
        val torrent = createTvTorrent("provider-error-torrent", "providererrorhash$statusCode")
        mockServer.`when`(cacheCheckRequest()).respond(
            response().withStatusCode(statusCode).withBody("""{"status": "error"}""")
        )

        //when
        runHealthCheck(torrent)

        //then
        mockServer.verify(cacheCheckRequest(), VerificationTimes.atLeast(1))
        assertNoRepairFor(torrent)
    }

    @Test
    fun `a check while the provider's quota gate is open does not repair the torrent`() {
        //given: the state Premiumize's client is in for waitAfterQuotaExceeded after a quota error
        val torrent = createTvTorrent("gated-torrent", "gatedhash")
        premiumizeClient.trip(Instant.now().plus(Duration.ofHours(1)), "test")

        //when
        runHealthCheck(torrent)

        //then: the gate answered for Premiumize without a request
        mockServer.verify(cacheCheckRequest(), VerificationTimes.never())
        assertNoRepairFor(torrent)
    }

    @Test
    fun `a file Sonarr sees under another mount path is kept, not deleted`() {
        //given: Sonarr imported the file, but sees debridav's mount at /mnt/media, not at mount-path /data
        createTvTorrent("mount-mismatch-torrent", "mountmismatchhash")
        val localPath = "/downloads/tv/mount-mismatch-torrent/mount-mismatch-torrent.mkv"
        premiumizeStubbingService.mockIsNotCached()
        stubSonarrEpisodeFileAt("/mnt/media$localPath")

        //when
        torrentHealthCheckService.checkTorrentHealth()

        //then
        waitForCondition("repair outcome recorded") { repairOutcomeRepository.findAll().any() }
        assertThat(repairOutcomeRepository.findAll().single().action?.name, `is`("KEPT"))
        assertThat("file still in debridav", databaseFileService.getFileAtPath(localPath) != null, `is`(true))
        mockServer.verify(
            request().withMethod("DELETE").withPath("/sonarr/api/v3/episodefile/10"), VerificationTimes.never()
        )
        mockServer.verify(request().withMethod("POST").withPath("/sonarr/api/v3/command"), VerificationTimes.never())
    }

    @Test
    fun `repairs over the hourly limit are throttled, not run`() {
        //given: the hour's only repair has already run
        healthCheckConfigurationProperties.maxRepairsPerHour = 1
        repairOutcomeRepository.save(RepairOutcome().apply {
            queueName = "torrent_health_repair"
            msgId = 0
            action = RepairAction.REPAIRED
        })
        val localPath = "/downloads/tv/throttled-torrent/throttled-torrent.mkv"
        createTvTorrent("throttled-torrent", "throttledhash")
        premiumizeStubbingService.mockIsNotCached()
        stubSonarrEpisodeFileAt("/data$localPath")

        //when
        torrentHealthCheckService.checkTorrentHealth()

        //then
        waitForCondition("second repair outcome recorded") { repairOutcomeRepository.findAll().count() == 2 }
        assertThat(
            repairOutcomeRepository.findAll().map { it.action?.name },
            containsInAnyOrder("REPAIRED", "THROTTLED")
        )
        assertThat("file still in debridav", databaseFileService.getFileAtPath(localPath) != null, `is`(true))
        mockServer.verify(request().withPath("/sonarr/.*"), VerificationTimes.never())
    }

    /** Sonarr knows the release, has it imported as episode file 10, and reports that file at [path]. */
    private fun stubSonarrEpisodeFileAt(path: String) {
        mockServer.`when`(request().withMethod("GET").withPath("/sonarr/api/v3/history")).respond(
            response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"page": 1, "pageSize": 1, "totalRecords": 1, "records": [{"id": 42}]}""")
        )
        mockServer.`when`(request().withMethod("POST").withPath("/sonarr/api/v3/history/failed/42"))
            .respond(response().withStatusCode(200))
        mockServer.`when`(request().withMethod("GET").withPath("/sonarr/api/v3/parse")).respond(
            response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"episodes": [{"id": 1, "episodeFileId": 10}]}""")
        )
        mockServer.`when`(request().withMethod("GET").withPath("/sonarr/api/v3/episodefile/10")).respond(
            response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON)
                .withBody("""{"id": 10, "path": "$path"}""")
        )
        mockServer.`when`(request().withMethod("DELETE").withPath("/sonarr/api/v3/episodefile/10"))
            .respond(response().withStatusCode(200))
        mockServer.`when`(request().withMethod("POST").withPath("/sonarr/api/v3/command"))
            .respond(response().withStatusCode(200))
    }

    /** A torrent in Sonarr's category, so a repair would blocklist it and search again. */
    private fun createTvTorrent(name: String, hash: String): Torrent {
        val category = categoryRepository.findByNameIgnoreCase("tv")
            ?: categoryRepository.save(Category("tv", "/data/downloads/tv"))
        val contents = DebridCachedTorrentContent(
            originalPath = "$name.mkv",
            size = 1_000_000L,
            modified = Instant.EPOCH.toEpochMilli(),
            magnet = MAGNET,
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf(
                MissingFile(DebridProvider.PREMIUMIZE, Instant.EPOCH.toEpochMilli())
            )
        )
        val file = databaseFileService.createDebridFile("/downloads/tv/$name/$name.mkv", hash, contents)
        databaseFileService.saveDbEntity(file)
        val torrent = Torrent().apply {
            this.name = name
            this.category = category
            this.hash = hash
            magnet = MAGNET
            savePath = "/data/downloads/tv"
            status = Status.LIVE
            lastVerified = null
        }
        torrentRepository.save(torrent)
        torrent.files = mutableListOf(file)
        return torrentRepository.save(torrent)
    }

    /** Runs a sweep and waits until the check handler is done with [torrent]. */
    private fun runHealthCheck(torrent: Torrent) {
        torrentHealthCheckService.checkTorrentHealth()
        waitForCondition("health check of torrent ${torrent.id} handled") {
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
        mockServer.verify(request().withPath("/sonarr/.*"), VerificationTimes.never())
    }

    private fun cacheCheckRequest(): HttpRequest =
        request().withMethod("GET").withPath("/premiumize/cache/check")

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
