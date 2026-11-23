package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.NzbContents
import io.skjaere.debridav.health.RepairAction
import io.skjaere.debridav.health.RepairOutcomeRepository
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.UsenetDownloadStatus
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import io.skjaere.debridav.usenet.pgmq.NzbHealthRepairHandler
import io.skjaere.debridav.usenet.pgmq.NzbHealthRepairMessage
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

private const val FILE_PATH = "/downloads/nzb-repair/Some.Release/episode.mkv"

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class NzbRepairDeleteIT {

    @Autowired
    lateinit var nzbHealthRepairHandler: NzbHealthRepairHandler

    @Autowired
    lateinit var databaseFileService: DatabaseFileService

    @Autowired
    lateinit var nzbDocumentRepository: NzbDocumentRepository

    @Autowired
    lateinit var usenetRepository: UsenetRepository

    @Autowired
    lateinit var repairOutcomeRepository: RepairOutcomeRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var mockServer: ClientAndServer

    @BeforeEach
    fun setUp() {
        // Other test classes in the same context leave repair outcomes behind.
        repairOutcomeRepository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        databaseFileService.getFileAtPath(FILE_PATH)?.let { databaseFileService.deleteFile(it) }
        usenetRepository.deleteAll()
        repairOutcomeRepository.deleteAll()
        mockServer.reset()
    }

    @Test
    fun `a repair that deletes an NZB's files removes them from the filesystem`() {
        //given: an imported NZB release whose articles are gone, and no arr that has it imported
        val documentId = seedImportedRelease()
        mockServer.`when`(request().withMethod("GET").withPath("/sonarr/api/v3/parse")).respond(
            response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody("""{"episodes": []}""")
        )
        mockServer.`when`(request().withMethod("GET").withPath("/radarr/api/v3/parse")).respond(
            response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody("""{"movie": null}""")
        )

        //when
        nzbHealthRepairHandler.handle(NzbHealthRepairMessage(documentId, "missing articles", "Some.Release"), 1L)

        //then
        assertThat(repairOutcomeRepository.findAll().map { it.action }, contains(RepairAction.DELETED))
        assertThat("file in the filesystem", databaseFileService.getFileAtPath(FILE_PATH), nullValue())
    }

    private fun seedImportedRelease(): Long = TransactionTemplate(transactionManager).execute {
        val document = nzbDocumentRepository.save(NzbDocumentEntity().apply { name = "Some.Release" })
        val file = databaseFileService.createDebridFile(
            FILE_PATH,
            "nzbrepairhash",
            NzbContents().apply {
                originalPath = "episode.mkv"
                size = 1_000L
                modified = Instant.EPOCH.toEpochMilli()
                mimeType = "video/x-matroska"
                nzbDocument = document
            }
        )
        databaseFileService.saveDbEntity(file)
        usenetRepository.save(UsenetDownload().apply {
            name = "Some.Release"
            hash = "nzbrepairhash"
            status = UsenetDownloadStatus.COMPLETED
            nzbDocument = document
            debridFiles = mutableListOf(file)
        })
        document.id!!
    }!!
}
