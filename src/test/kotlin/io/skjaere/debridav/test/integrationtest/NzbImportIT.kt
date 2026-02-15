package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdFullHistoryResponse
import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.hasProperty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=easynews", "nntp.enabled=true"]
)
@MockServerTest
class NzbImportIT {

    @Autowired
    private lateinit var usenetRepository: UsenetRepository

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var mockNntpServerContainer: MockNntpServerContainer

    @LocalServerPort
    var randomServerPort: Int = 0

    private val deserializer = Json { ignoreUnknownKeys = true }
    private val sardine = SardineFactory.begin()

    @AfterEach
    fun tearDown() {
        runBlocking { mockNntpServerContainer.client.clearYencBodyExpectations() }
    }

    @Test
    fun `NZB import with RAR4 single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.RAR4, "rar4-test-release")
    }

    @Test
    fun `NZB import with RAR5 single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.RAR5, "rar5-test-release")
    }

    @Test
    fun `NZB import with 7zip single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.SEVENZIP, "7zip-test-release")
    }

    private fun nzbImportTest(containerType: ContainerType, releaseName: String) {
        // given - test data must be >16KB for nzb-streamer enrichment
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = containerType
            )
        }

        // when - upload NZB via SABnzbd API
        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("cat", "testcat")
        parts.part("name", nzbXml.toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=$releaseName.nzb")

        webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(parts.build())).exchange().expectStatus().is2xxSuccessful

        // then - poll history until COMPLETED or timeout
        val historyParts = MultipartBodyBuilder()
        historyParts.part("mode", "history")
        historyParts.part("cat", "testcat")

        var completed = false
        var lastStatus = "unknown"
        for (attempt in 1..30) {
            Thread.sleep(1000)
            val historyBody = webTestClient.post().uri("/api")
                .body(BodyInserters.fromMultipartData(historyParts.build()))
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody(String::class.java)
                .returnResult().responseBody ?: continue

            val history = deserializer.decodeFromString<SabnzbdFullHistoryResponse>(historyBody)
            val slot = history.history.slots.firstOrNull { it.name == releaseName }
            if (slot != null) {
                lastStatus = slot.status
                if (slot.status == "COMPLETED") {
                    completed = true
                    break
                }
                if (slot.status == "FAILED") {
                    break
                }
            }
        }

        assertThat(
            "Import should complete within timeout (last status: $lastStatus)",
            completed,
            `is`(true)
        )

        // verify WebDAV has the release directory
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/downloads/"),
            hasItem<DavResource>(hasProperty("displayName", `is`(releaseName)))
        )

        // verify WebDAV has the extracted file inside the release directory
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/downloads/$releaseName"),
            hasItem<DavResource>(hasProperty("displayName", `is`("testfile.bin")))
        )

        // verify history shows correct storage path
        webTestClient.post().uri("/api")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots[?(@.name=='$releaseName')].storage")
            .isEqualTo("/data/downloads/$releaseName")

        // cleanup
        sardine.delete("http://localhost:${randomServerPort}/downloads/$releaseName")
        usenetRepository.deleteAll()
    }
}
