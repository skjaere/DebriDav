package io.skjaere.debridav.test.integrationtest

import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.health.PgmqHealthQueueRepository
import io.skjaere.debridav.repository.NzbImportRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.usenet.NzbImportService
import io.skjaere.debridav.usenet.StuckNzbImportReconciler
import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.UsenetDownloadStatus
import io.skjaere.debridav.usenet.pgmq.NzbImportMessage
import io.skjaere.debridav.usenet.queue.NzbImportRecord
import io.skjaere.debridav.usenet.queue.NzbImportStatus
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class NzbImportQueueIT {

    @Autowired
    lateinit var nzbImportService: NzbImportService

    @Autowired
    lateinit var stuckNzbImportReconciler: StuckNzbImportReconciler

    @Autowired
    lateinit var usenetRepository: UsenetRepository

    @Autowired
    lateinit var nzbImportRepository: NzbImportRepository

    @Autowired
    lateinit var pgmqHealthQueueRepository: PgmqHealthQueueRepository

    @Autowired
    lateinit var pgmqClient: PgmqClient

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val recordIds = mutableListOf<Long>()

    @AfterEach
    fun tearDown() {
        recordIds.forEach { id ->
            jdbcTemplate.queryForList(
                "SELECT msg_id FROM pgmq.q_nzb_import WHERE (message->>'nzbImportRecordId')::bigint = ?",
                Long::class.java, id
            ).forEach { jdbcTemplate.queryForObject("SELECT pgmq.delete('nzb_import', ?)", Boolean::class.java, it) }
            nzbImportRepository.findById(id).ifPresent { record ->
                nzbImportRepository.delete(record)
                record.usenetDownloadId?.let { usenetRepository.deleteById(it) }
            }
        }
    }

    @Test
    fun `an import is queued only once the transaction that saved its rows commits`() {
        val recordId = TransactionTemplate(transactionManager).execute {
            val (download, record) = queuedImport("committed-later")
            nzbImportService.scheduleImport("<nzb/>".toByteArray(), download, record.id!!)

            // A worker reading the message now would not find the rows, skip the import as
            // deleted, and leave the download QUEUED forever.
            assertThat(
                "queued before commit",
                record.id in pgmqHealthQueueRepository.queuedImportRecordIds(),
                `is`(false)
            )
            record.id!!
        }!!

        assertThat("queued after commit", recordId in pgmqHealthQueueRepository.queuedImportRecordIds(), `is`(true))
    }

    @Test
    fun `an import stuck without a queued message past the retry window is failed`() {
        //given
        val (stuckDownload, stuck) = TransactionTemplate(transactionManager).execute { queuedImport("stuck") }!!
        val (_, waiting) = TransactionTemplate(transactionManager).execute { queuedImport("waiting") }!!
        val (_, recent) = TransactionTemplate(transactionManager).execute { queuedImport("recent") }!!
        listOf(stuck, waiting).forEach {
            jdbcTemplate.update("UPDATE nzb_import SET updated_at = now() - interval '1 day' WHERE id = ?", it.id)
        }
        // Still waiting for a worker: its message is in the queue.
        pgmqClient.send("nzb_import", NzbImportMessage("", waiting.usenetDownloadId!!, waiting.id!!))

        //when
        stuckNzbImportReconciler.failStuckImports()

        //then
        assertThat(nzbImportRepository.findById(stuck.id!!).get().status, `is`(NzbImportStatus.FAILED))
        assertThat(usenetRepository.findById(stuckDownload.id!!).get().status, `is`(UsenetDownloadStatus.FAILED))
        assertThat(nzbImportRepository.findById(waiting.id!!).get().status, not(NzbImportStatus.FAILED))
        assertThat(nzbImportRepository.findById(recent.id!!).get().status, not(NzbImportStatus.FAILED))
    }

    private fun queuedImport(name: String): Pair<UsenetDownload, NzbImportRecord> {
        val download = usenetRepository.save(UsenetDownload().apply {
            this.name = name
            hash = "$name-hash"
            status = UsenetDownloadStatus.QUEUED
        })
        val record = nzbImportRepository.save(NzbImportRecord().apply {
            usenetDownloadId = download.id
            this.name = name
            status = NzbImportStatus.QUEUED
        })
        recordIds += record.id!!
        return download to record
    }
}
