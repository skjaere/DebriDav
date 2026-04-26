package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.mocknntp.testcontainer.client.NzbFileSpec
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerNntpTest
import io.skjaere.debridav.test.integrationtest.config.awaitSabImportCompletion
import io.skjaere.debridav.test.integrationtest.config.awaitSabImportFailure
import io.skjaere.debridav.usenet.nzb.NzbArchiveType
import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
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
    properties = ["debridav.debrid-clients=easynews"]
)
@MockServerNntpTest
class NzbImportIT {

    @Autowired
    private lateinit var usenetRepository: UsenetRepository

    @Autowired
    private lateinit var nzbDocumentRepository: NzbDocumentRepository

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var mockNntpServerContainer: MockNntpServerContainer

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    private val createdReleases = mutableListOf<String>()

    @AfterEach
    fun tearDown() {
        runBlocking { mockNntpServerContainer.client.clearYencBodyExpectations() }
        for (releaseName in createdReleases) {
            @Suppress("TooGenericExceptionCaught")
            try {
                sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/$releaseName")
            } catch (_: Exception) {
                // directory may not exist if import failed
            }
        }
        createdReleases.clear()
        usenetRepository.deleteAll()
        nzbDocumentRepository.deleteAll()
    }

    @Test
    fun `NZB import with RAR4 single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.RAR4, "rar4-test-release", NzbArchiveType.RAR)
    }

    @Test
    fun `NZB import with RAR5 single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.RAR5, "rar5-test-release", NzbArchiveType.RAR)
    }

    @Test
    fun `NZB import with 7zip single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.SEVENZIP, "7zip-test-release", NzbArchiveType.SEVEN_ZIP)
    }

    @Test
    fun `NZB import with non-archive data produces completed download with raw metadata`() {
        val releaseName = "raw-data-test"
        createdReleases.add(releaseName)
        // Garbage data that is not a valid archive format — treated as Raw metadata
        val garbageData = ByteArray(32 * 1024) { 0x42 }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareNzb(
                files = listOf(NzbFileSpec("data.bin", garbageData))
            )
        }

        uploadNzb(nzbXml, releaseName)
        waitForCompletion(releaseName)

        // verify history shows COMPLETED status
        val historyParts = MultipartBodyBuilder()
        historyParts.part("mode", "history")
        historyParts.part("cat", "testcat")
        webTestClient.post().uri("/api")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots[?(@.name=='$releaseName')].status")
            .isEqualTo("COMPLETED")

        // verify archive type is RAW
        val nzbDocuments = nzbDocumentRepository.findAll().toList()
        assertThat("Should have exactly one NZB document", nzbDocuments.size, `is`(1))
        assertThat("Archive type should be RAW", nzbDocuments[0].archiveType, `is`(NzbArchiveType.RAW))
    }

    @Test
    @Disabled
    fun `adding the same NZB twice overwrites DB entries and files remain accessible`() {
        val releaseName = "duplicate-nzb-test"
        createdReleases.add(releaseName)
        val containerType = ContainerType.RAR5
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = containerType
            )
        }

        // first upload
        uploadNzb(nzbXml, releaseName)
        waitForCompletion(releaseName)

        val entriesAfterFirst = usenetRepository.findAll().filter { it.name == releaseName }
        assertThat("Should have exactly one DB entry after first import", entriesAfterFirst.size, `is`(1))

        // clear NNTP expectations and re-prepare so the second upload can also be served
        runBlocking { mockNntpServerContainer.client.clearYencBodyExpectations() }
        val nzbXml2 = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = containerType
            )
        }

        // second upload of the same release
        uploadNzb(nzbXml2, releaseName)
        waitForCompletion(releaseName)

        // verify only one DB entry exists (overwritten, not duplicated)
        val entriesAfterSecond = usenetRepository.findAll().filter { it.name == releaseName }
        assertThat("Should still have exactly one DB entry after second import", entriesAfterSecond.size, `is`(1))

        // verify WebDAV still has the release directory with the file
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$releaseName"),
            hasItem<DavResource>(hasProperty("displayName", `is`("testfile.bin")))
        )
    }

    private fun nzbImportTest(containerType: ContainerType, releaseName: String, expectedArchiveType: NzbArchiveType) {
        createdReleases.add(releaseName)
        // given - test data must be >16KB for nzb-streamer enrichment
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = containerType
            )
        }

        // when - upload NZB via SABnzbd API
        uploadNzb(nzbXml, releaseName)

        // then - poll history until COMPLETED or timeout
        waitForCompletion(releaseName)

        // verify WebDAV has the release directory
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/"),
            hasItem<DavResource>(hasProperty("displayName", `is`(releaseName)))
        )

        // verify WebDAV has the extracted file inside the release directory
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$releaseName"),
            hasItem<DavResource>(hasProperty("displayName", `is`("testfile.bin")))
        )

        // verify history shows correct storage path
        val historyParts = MultipartBodyBuilder()
        historyParts.part("mode", "history")
        historyParts.part("cat", "testcat")
        webTestClient.post().uri("/api")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots[?(@.name=='$releaseName')].storage")
            .isEqualTo("/data/downloads/$releaseName")

        // verify the persisted archive type
        val nzbDocuments = nzbDocumentRepository.findAll().toList()
        assertThat("Should have exactly one NZB document", nzbDocuments.size, `is`(1))
        assertThat("Archive type should match", nzbDocuments[0].archiveType, `is`(expectedArchiveType))
    }

    private fun uploadNzb(nzbXml: String, releaseName: String) {
        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("cat", "testcat")
        parts.part("name", nzbXml.toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=$releaseName.nzb")

        webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(parts.build())).exchange().expectStatus().is2xxSuccessful
    }

    private fun waitForFailure(releaseName: String) =
        webTestClient.awaitSabImportFailure(releaseName)

    private fun waitForCompletion(releaseName: String) =
        webTestClient.awaitSabImportCompletion(releaseName)
}
