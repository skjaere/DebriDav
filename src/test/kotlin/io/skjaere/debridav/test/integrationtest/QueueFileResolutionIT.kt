package io.skjaere.debridav.test.integrationtest

import com.github.sardine.SardineFactory
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DbDirectory
import io.skjaere.debridav.repository.NzbImportRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerNntpTest
import io.skjaere.debridav.test.integrationtest.config.awaitSabImportCompletion
import io.skjaere.debridav.usenet.queue.NzbImportFileJson
import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import tools.jackson.module.kotlin.jacksonObjectMapper

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=easynews"]
)
@MockServerNntpTest
class QueueFileResolutionIT {

    @Autowired
    private lateinit var usenetRepository: UsenetRepository

    @Autowired
    private lateinit var nzbImportRepository: NzbImportRepository

    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var mockNntpServerContainer: MockNntpServerContainer

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    @AfterEach
    fun tearDown() {
        runBlocking { mockNntpServerContainer.client.clearYencBodyExpectations() }
        @Suppress("TooGenericExceptionCaught")
        try {
            sardine.delete("http://localhost:${randomServerPort}/movies/")
        } catch (_: Exception) { }
        @Suppress("TooGenericExceptionCaught")
        try {
            sardine.delete("http://localhost:${randomServerPort}/downloads/queue-file-resolution-test")
        } catch (_: Exception) { }
        usenetRepository.deleteAll()
        nzbImportRepository.deleteAll()
    }

    @Test
    fun `queue files endpoint returns updated paths after file is moved`() {
        val releaseName = "queue-file-resolution-test"

        // given - prepare and import an NZB
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = ContainerType.RAR5
            )
        }
        uploadNzb(nzbXml, releaseName)
        waitForCompletion(releaseName)

        // find the import record id by name (other tests may leave records behind)
        val importId = nzbImportRepository.findAll()
            .first { it.name == releaseName }.id!!

        // verify initial file path points to downloads/
        val initialFiles = getQueueItemFiles(importId)
        assertThat("Should have one file", initialFiles.size, `is`(1))
        assertThat(
            "Initial path should be under /downloads, got: ${initialFiles[0].path}",
            initialFiles[0].path.startsWith("/downloads/$releaseName/"),
            `is`(true)
        )

        // when - move the release directory using DatabaseFileService
        val releaseDir = databaseFileService.getFileAtPath("/downloads/$releaseName") as DbDirectory
        databaseFileService.createDirectory("/movies")
        databaseFileService.moveResource(releaseDir, "/movies/$releaseName", releaseName)

        // then - queue files endpoint should return the new path
        val movedFiles = getQueueItemFiles(importId)
        assertThat("Should still have one file", movedFiles.size, `is`(1))
        assertThat(
            "Path should now be under /movies, got: ${movedFiles[0].path}",
            movedFiles[0].path.startsWith("/movies/$releaseName/"),
            `is`(true)
        )
        assertThat(
            "File name should be preserved",
            movedFiles[0].path.endsWith("/testfile.bin"),
            `is`(true)
        )
    }

    private fun getQueueItemFiles(importId: Long): List<NzbImportFileJson> {
        val body = webTestClient.get().uri("/api/v1/queue/$importId/files")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult().responseBody!!

        val mapper = jacksonObjectMapper()
        val type = mapper.typeFactory
            .constructCollectionType(List::class.java, NzbImportFileJson::class.java)
        return mapper.readValue(body, type)
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

    private fun waitForCompletion(releaseName: String) =
        webTestClient.awaitSabImportCompletion(releaseName)
}
