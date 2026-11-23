package io.skjaere.debridav.test.integrationtest

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.stream.StreamingService
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.deepCopy
import io.skjaere.debridav.test.integrationtest.config.ContentStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import org.apache.commons.codec.digest.DigestUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFalse

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@MockServerTest
class StreamingEofIT {
    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var contentStubbingService: ContentStubbingService

    @Autowired
    lateinit var debridFileContentsRepository: DebridFileContentsRepository

    @Autowired
    lateinit var mockserverClient: ClientAndServer

    @AfterEach
    fun tearDown() {
        mockserverClient.reset()
    }

    @Test
    fun `that premature EOF from upstream does not result in ERROR log`() {
        // given - file claims to be 1000 bytes but server only returns 9 bytes ("it works!")
        // This simulates a debrid provider sending fewer bytes than promised, causing premature EOF
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("eof-test")
        fileContents.size = 1000L

        val debridLink = CachedFile(
            "testfile-eof.mp4",
            link = "http://localhost:${contentStubbingService.port}/truncatedLink",
            size = 1000L,
            provider = DebridProvider.PREMIUMIZE,
            lastChecked = Instant.now().toEpochMilli(),
            params = mapOf(),
            mimeType = "video/mp4"
        )
        fileContents.debridLinks = mutableListOf(debridLink)
        contentStubbingService.mockTruncatedStream()
        databaseFileService.createDebridFile("/testfile-eof.mp4", hash, fileContents)
            .let { debridFileContentsRepository.save(it) }

        // Capture StreamingService logs to verify no ERROR-level log is generated for EOF
        val streamingLogger = LoggerFactory.getLogger(StreamingService::class.java) as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        streamingLogger.addAppender(listAppender)

        try {
            // when - make request; server sends 9 bytes but we expect 1000, triggering premature EOF
            // The EOFException thrown by readAvailable returning -1 before all bytes are consumed
            // should be handled gracefully at WARN level (not ERROR), so Sentry does not capture it
            try {
                webTestClient
                    .mutate().responseTimeout(Duration.ofMillis(30000)).build()
                    .get()
                    .uri("/testfile-eof.mp4")
                    .exchange()
            } catch (_: Exception) {
                // Connection may close early due to EOF handling upstream; this is expected
            }

            // then - no ERROR log should be generated for premature EOF from upstream HTTP stream.
            // Before the fix, EOFException was caught by the generic Exception handler which logged
            // at ERROR level, causing Sentry to fire false alerts for normal network conditions.
            // After the fix, it is caught by the kotlinx.io.IOException handler and logged at WARN.
            val errorLogs = listAppender.list.filter {
                it.level == Level.ERROR && it.formattedMessage.contains("An error occurred during streaming")
            }
            assertFalse(
                errorLogs.isNotEmpty(),
                "Expected no ERROR log for premature EOF from upstream, but found: " +
                        errorLogs.map { it.formattedMessage }
            )
        } finally {
            streamingLogger.detachAppender(listAppender)
        }
    }
}
