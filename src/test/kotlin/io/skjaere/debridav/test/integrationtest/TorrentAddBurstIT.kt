package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import io.skjaere.debridav.test.support.SubtreeCleanupService
import io.skjaere.debridav.torrent.TorrentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Duration

private const val BURST_SIZE = 50
private const val PER_REQUEST_TIMEOUT_S = 60L

// All burst torrents nest under this single subdirectory so cleanup is one LTree DELETE.
private const val BURST_DIR_NAME = "burstTest"
private const val BURST_BASE_PATH = "/downloads/$BURST_DIR_NAME"

/**
 * Simulates a burst of `BURST_SIZE` concurrent /api/v2/torrents/add requests so we can
 * observe how the torrent-add path behaves under the same kind of load that an arr-driven
 * repair pipeline would produce after blocklisting many releases at once.
 *
 * Each magnet has a unique info hash and display name so the resulting torrent rows and
 * file paths don't collide; each one resolves to the same single-file fixture from the
 * Premiumize mock.
 */
@SpringBootTest(
    classes = [
        DebriDavApplication::class,
        IntegrationTestContextConfiguration::class,
        MiltonConfiguration::class,
        TestSecurityConfiguration::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class TorrentAddBurstIT {
    private val logger = LoggerFactory.getLogger(TorrentAddBurstIT::class.java)

    @Autowired
    private lateinit var subtreeCleanup: SubtreeCleanupService

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

    @BeforeEach
    fun setUp() {
        // Other ITs share the testcontainer DB — start from a clean torrent table so the
        // post-burst row count assertion only sees what this test produced.
        torrentRepository.deleteAll()
        subtreeCleanup.deleteDirectorySubtree(BURST_BASE_PATH)
    }

    @AfterEach
    fun tearDown() {
        mockserverClient.reset()
        torrentRepository.deleteAll()
        // One LTree subtree DELETE wipes burstTest plus every burst-test-N child the
        // 50 concurrent adds created — much faster than 50 individual sardine DELETEs.
        subtreeCleanup.deleteDirectorySubtree(BURST_BASE_PATH)
    }

    @Test
    fun `handles a burst of torrent add requests without errors`() {
        // given — premiumize answers any cache check + directdl identically
        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()

        val magnets = (1..BURST_SIZE).map { i ->
            // Unique 40-char info hash + unique display name so rows + paths don't collide.
            // dn= is URL-decoded by getNameFromMagnet, so %2F gives us a nested path under
            // /downloads/burstTest/burst-test-N for single-shot teardown.
            val hash = "%040x".format(i)
            "magnet:?xt=urn:btih:$hash&dn=$BURST_DIR_NAME%2Fburst-test-$i&tr="
        }

        // when — fire all requests concurrently and time the burst
        val startedAt = System.currentTimeMillis()
        val statuses = runBlocking(Dispatchers.IO) {
            magnets.map { magnet ->
                async {
                    val parts = MultipartBodyBuilder()
                    parts.part("urls", magnet)
                    parts.part("category", "test")
                    parts.part("paused", "false")

                    webTestClient
                        .mutate().responseTimeout(Duration.ofSeconds(PER_REQUEST_TIMEOUT_S)).build()
                        .post().uri("/api/v2/torrents/add")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(parts.build()))
                        .exchange()
                        .returnResult(String::class.java)
                        .status.value()
                }
            }.awaitAll()
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        logger.info("burst of {} adds completed in {}ms", BURST_SIZE, elapsedMs)

        // then — every request succeeded
        val byStatus = statuses.groupingBy { it }.eachCount()
        assertEquals(
            BURST_SIZE,
            statuses.count { it in 200..299 },
            "Expected $BURST_SIZE successful 2xx responses, got: $byStatus",
        )

        // and the database reflects exactly that many torrents
        val torrentCount = torrentRepository.findAll().toList().size
        assertEquals(BURST_SIZE, torrentCount, "torrent_repository row count mismatch")

        // and every torrent's downloaded file landed at its own path
        magnets.indices.forEach { i ->
            val path = "$BURST_BASE_PATH/burst-test-${i + 1}/a/b/c/movie.mkv"
            val entity = databaseFileService.getFileAtPath(path)
            assertEquals(
                "movie.mkv",
                entity?.name,
                "expected a debrid file at $path but found: $entity",
            )
        }
    }
}
