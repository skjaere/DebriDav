package io.skjaere.debridav.test.integrationtest

import com.github.sardine.SardineFactory
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerNntpTest
import io.skjaere.debridav.test.integrationtest.config.awaitSabImportCompletion
import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import kotlinx.coroutines.runBlocking
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
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debridav.debrid-clients=easynews",
        "sonarr.integration-enabled=true",
        "sonarr.category=testcat"
    ]
)
@MockServerNntpTest
class NzbStreamingRepairIT {

    @Autowired
    private lateinit var usenetRepository: UsenetRepository

    @Autowired
    private lateinit var nzbDocumentRepository: NzbDocumentRepository

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var mockNntpServerContainer: MockNntpServerContainer

    @Autowired
    private lateinit var mockServer: ClientAndServer

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()
    private val createdReleases = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        usenetRepository.deleteAll()
        nzbDocumentRepository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            mockNntpServerContainer.client.clearYencBodyExpectations()
            mockNntpServerContainer.client.clearStatExpectations()
        }
        for (releaseName in createdReleases) {
            @Suppress("TooGenericExceptionCaught")
            try {
                sardine.delete("http://localhost:$randomServerPort/webdav/downloads/$releaseName")
            } catch (_: Exception) {
                // directory may not exist if import failed
            }
        }
        createdReleases.clear()
        usenetRepository.deleteAll()
        nzbDocumentRepository.deleteAll()
        mockServer.reset()
    }

    @Test
    @Suppress("LongMethod")
    fun `streaming failure on missing article triggers Arr repair`() {
        val releaseName = "streaming-repair-test-release"
        createdReleases.add(releaseName)

        // given - import an NZB successfully
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = ContainerType.RAR5
            )
        }
        uploadNzb(nzbXml, releaseName)
        waitForCompletion(releaseName)

        // given - remove a yenc body mock so BODY returns 430 during streaming
        val nzbDocument = nzbDocumentRepository.findAll().first()
        val articleId = nzbDocument.files.first().segments.first().articleId
        runBlocking {
            mockNntpServerContainer.client.removeYencBodyExpectation("<$articleId>")
        }

        // given - set up Sonarr mock expectations
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
        mockServer.`when`(
            request()
                .withMethod("DELETE")
                .withPath("/sonarr/api/v3/episodefile/10")
        ).respond(
            response().withStatusCode(200)
        )
        mockServer.`when`(
            request()
                .withMethod("POST")
                .withPath("/sonarr/api/v3/command")
        ).respond(
            response().withStatusCode(200)
        )

        // when - trigger streaming (WebDAV GET), which hits ArticleNotFoundException
        @Suppress("TooGenericExceptionCaught")
        try {
            sardine.get("http://localhost:$randomServerPort/webdav/downloads/$releaseName/testfile.bin")
        } catch (_: Exception) {
            // the stream will fail since the article is missing — that's expected
        }

        // then - wait for the repair pipeline to call Sonarr
        waitForMockServerVerification {
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
    }

    private fun uploadNzb(nzbXml: String, releaseName: String) {
        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("cat", "testcat")
        parts.part("name", nzbXml.toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=$releaseName.nzb")

        webTestClient.post().uri("/api")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
    }

    private fun waitForCompletion(releaseName: String) =
        webTestClient.awaitSabImportCompletion(releaseName)

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
}
