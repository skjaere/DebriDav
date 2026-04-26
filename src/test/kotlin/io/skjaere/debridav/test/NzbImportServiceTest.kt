package io.skjaere.debridav.test

import com.vdsirotkin.pgmq.PgmqClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.NzbContents
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.NzbImportRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.NzbImportService
import io.skjaere.debridav.usenet.NzbImportTaskData
import io.skjaere.debridav.usenet.UsenetDownload
import io.skjaere.debridav.usenet.UsenetDownloadStatus
import io.skjaere.debridav.usenet.nzb.NzbArchiveType
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import io.skjaere.debridav.usenet.nzb.StreamableFileJson
import io.skjaere.debridav.usenet.queue.NzbImportRecord
import io.skjaere.debridav.usenet.queue.NzbImportStatus
import io.skjaere.nntp.ArticleNotFoundException
import io.skjaere.nntp.NntpConnectionException
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbFile
import io.skjaere.nzbstreamer.nzb.NzbSegment
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.nntp.YencHeaders
import java.io.IOException
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.*
import kotlin.test.assertEquals

class NzbImportServiceTest {

    private val nzbStreamer = mockk<NzbStreamer>()
    private val nzbDocumentRepository = mockk<NzbDocumentRepository>()
    private val usenetRepository = mockk<UsenetRepository>()
    private val nzbImportRepository = mockk<NzbImportRepository>()
    private val pgmqClient = mockk<PgmqClient>()
    private val databaseFileService = mockk<DatabaseFileService>()
    private val config = DebridavConfigurationProperties().apply {
        downloadPath = "/downloads"
        mountPath = "/data"
        debridClients = listOf(DebridProvider.EASYNEWS)
        waitAfterMissing = Duration.ZERO
        waitAfterProviderError = Duration.ZERO
        waitAfterNetworkError = Duration.ZERO
        waitAfterClientError = Duration.ZERO
        retriesOnProviderError = 0
        delayBetweenRetries = Duration.ZERO
        connectTimeoutMilliseconds = 5000
        readTimeoutMilliseconds = 30000
        shouldDeleteNonWorkingFiles = false
        torrentLifetime = Duration.ofHours(1)
        defaultCategories = emptyList()
        localEntityMaxSizeMb = 100
    }

    /**
     * A no-op PlatformTransactionManager that does not start real DB transactions.
     * TransactionTemplate will still execute the callback directly, which is all
     * we need for unit tests that already mock the repository methods.
     */
    private val platformTransactionManager = mockk<PlatformTransactionManager>(relaxed = true)

    private val underTest = NzbImportService(
        nzbStreamer, nzbDocumentRepository, usenetRepository,
        nzbImportRepository, pgmqClient, databaseFileService, config,
        platformTransactionManager
    )

    private val nzbBytes = "<nzb>test</nzb>".toByteArray()
    private val nzbBytesBase64 = Base64.getEncoder().encodeToString(nzbBytes)

    private fun createUsenetDownload(id: Long = 1L): UsenetDownload {
        val download = UsenetDownload()
        download.id = id
        download.name = "test-release"
        download.hash = "abc123"
        download.status = UsenetDownloadStatus.QUEUED
        return download
    }

    private fun createImportRecord(id: Long = 100L): NzbImportRecord {
        val record = NzbImportRecord()
        record.id = id
        record.name = "test-release"
        record.status = NzbImportStatus.QUEUED
        return record
    }

    @Test
    fun `executeImport sets COMPLETED on PrepareResult Success`() {
        // given
        val download = createUsenetDownload()
        val importRecord = createImportRecord()
        every { usenetRepository.findById(1L) } returns Optional.of(download)
        every { nzbImportRepository.findById(100L) } returns Optional.of(importRecord)

        val nzbFile = NzbFile(
            poster = "test", date = 0, subject = "test",
            groups = listOf("test"),
            segments = listOf(NzbSegment(bytes = 1000, number = 1, articleId = "seg@test")),
            yencHeaders = YencHeaders(line = 128, size = 5000, name = "file.rar", partEnd = 1000)
        )
        val nzbDoc = NzbDocument(listOf(nzbFile))
        val metadata = ExtractedMetadata.Archive(
            response = NzbMetadataResponse(
                volumes = listOf("file.rar"),
                obfuscated = false,
                entries = emptyList()
            ),
            orderedArchiveNzb = nzbDoc,
            entries = emptyList()
        )
        coEvery { nzbStreamer.prepare(any()) } returns PrepareResult.Success(metadata)

        val streamableFile = StreamableFile(
            path = "video.mkv", totalSize = 4000,
            startVolumeIndex = 0, startOffsetInVolume = 100,
            continuationHeaderSize = 0, endOfArchiveSize = 0
        )
        every { nzbStreamer.resolveStreamableFiles(metadata) } returns listOf(streamableFile)

        val savedDoc = NzbDocumentEntity().apply {
            id = 10L
            archiveType = NzbArchiveType.RAR
            streamableFiles = listOf(
                StreamableFileJson(
                    path = "video.mkv",
                    totalSize = 4000,
                    startVolumeIndex = 0,
                    startOffsetInVolume = 100,
                    continuationHeaderSize = 0,
                    endOfArchiveSize = 0
                )
            )
        }
        every { nzbDocumentRepository.save(any()) } returns savedDoc

        val debridFile = mockk<RemotelyCachedEntity>()
        every { databaseFileService.createDebridFiles(any(), any()) } returns listOf(debridFile)

        val savedSlot = slot<UsenetDownload>()
        every { usenetRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val importSlot = slot<NzbImportRecord>()
        every { nzbImportRepository.save(capture(importSlot)) } answers { importSlot.captured }

        // when
        underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 1L, 100L))

        // then
        assertEquals(UsenetDownloadStatus.COMPLETED, savedSlot.captured.status)
        assertEquals(NzbImportStatus.COMPLETED, importSlot.captured.status)
        assertEquals(4000L, importSlot.captured.size)
        assertEquals("RAR", importSlot.captured.archiveType)
        verify(exactly = 1) { nzbDocumentRepository.save(any()) }
        verify(exactly = 1) { databaseFileService.createDebridFiles(any(), eq("abc123")) }
    }

    @Test
    fun `executeImport sets FAILED on PrepareResult MissingArticles`() {
        // given
        val download = createUsenetDownload()
        val importRecord = createImportRecord()
        every { usenetRepository.findById(1L) } returns Optional.of(download)
        every { nzbImportRepository.findById(100L) } returns Optional.of(importRecord)

        coEvery { nzbStreamer.prepare(any()) } returns PrepareResult.MissingArticles(
            "Article not found: 430",
            ArticleNotFoundException("Article not found: 430")
        )

        val savedSlot = slot<UsenetDownload>()
        every { usenetRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val importSlot = slot<NzbImportRecord>()
        every { nzbImportRepository.save(capture(importSlot)) } answers { importSlot.captured }

        // when
        underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 1L, 100L))

        // then
        assertEquals(UsenetDownloadStatus.FAILED, savedSlot.captured.status)
        assertEquals(NzbImportStatus.FAILED, importSlot.captured.status)
        assertEquals("Article not found: 430", importSlot.captured.errorMessage)
        verify(exactly = 0) { nzbDocumentRepository.save(any()) }
    }

    @Test
    fun `executeImport sets FAILED on PrepareResult Failure`() {
        // given
        val download = createUsenetDownload()
        val importRecord = createImportRecord()
        every { usenetRepository.findById(1L) } returns Optional.of(download)
        every { nzbImportRepository.findById(100L) } returns Optional.of(importRecord)

        coEvery { nzbStreamer.prepare(any()) } returns PrepareResult.Failure(
            "Connection refused",
            NntpConnectionException("Connection refused")
        )

        val savedSlot = slot<UsenetDownload>()
        every { usenetRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val importSlot = slot<NzbImportRecord>()
        every { nzbImportRepository.save(capture(importSlot)) } answers { importSlot.captured }

        // when
        underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 1L, 100L))

        // then
        assertEquals(UsenetDownloadStatus.FAILED, savedSlot.captured.status)
        assertEquals(NzbImportStatus.FAILED, importSlot.captured.status)
        verify(exactly = 0) { nzbDocumentRepository.save(any()) }
    }

    @Test
    fun `executeImport sets FAILED on PrepareResult UnsupportedArchive`() {
        // given
        val download = createUsenetDownload()
        val importRecord = createImportRecord()
        every { usenetRepository.findById(1L) } returns Optional.of(download)
        every { nzbImportRepository.findById(100L) } returns Optional.of(importRecord)

        coEvery { nzbStreamer.prepare(any()) } returns PrepareResult.UnsupportedArchive(
            "Unable to detect archive type from filenames or byte signatures",
            IOException("Unable to detect archive type from filenames or byte signatures")
        )

        val savedSlot = slot<UsenetDownload>()
        every { usenetRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val importSlot = slot<NzbImportRecord>()
        every { nzbImportRepository.save(capture(importSlot)) } answers { importSlot.captured }

        // when
        underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 1L, 100L))

        // then
        assertEquals(UsenetDownloadStatus.FAILED, savedSlot.captured.status)
        assertEquals(NzbImportStatus.FAILED, importSlot.captured.status)
        verify(exactly = 0) { nzbDocumentRepository.save(any()) }
    }

    @Test
    fun `executeImport sets FAILED on unexpected exception`() {
        // given
        val download = createUsenetDownload()
        val importRecord = createImportRecord()
        every { usenetRepository.findById(1L) } returns Optional.of(download)
        every { nzbImportRepository.findById(100L) } returns Optional.of(importRecord)

        coEvery { nzbStreamer.prepare(any()) } throws RuntimeException("unexpected error")

        val savedSlot = slot<UsenetDownload>()
        every { usenetRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

        val importSlot = slot<NzbImportRecord>()
        every { nzbImportRepository.save(capture(importSlot)) } answers { importSlot.captured }

        // when
        underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 1L, 100L))

        // then
        assertEquals(UsenetDownloadStatus.FAILED, savedSlot.captured.status)
        assertEquals(NzbImportStatus.FAILED, importSlot.captured.status)
    }

    @Test
    fun `executeImport throws when UsenetDownload not found on initial load`() {
        // given
        every { usenetRepository.findById(999L) } returns Optional.empty()

        // when - should return early without error (download may have been deleted)
        underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 999L, 100L))

        // then - no exception, no save calls
        verify(exactly = 0) { usenetRepository.save(any<UsenetDownload>()) }
        verify(exactly = 0) { nzbImportRepository.save(any<NzbImportRecord>()) }
    }

    @Test
    fun `executeImport throws when NzbImportRecord not found`() {
        // given
        val download = createUsenetDownload()
        every { usenetRepository.findById(1L) } returns Optional.of(download)
        every { nzbImportRepository.findById(999L) } returns Optional.empty()

        // when/then
        kotlin.test.assertFailsWith<IllegalStateException> {
            underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 1L, 999L))
        }
    }

    @Test
    fun `executeImport always saves both records in finally block`() {
        // given
        val download = createUsenetDownload()
        val importRecord = createImportRecord()
        every { usenetRepository.findById(1L) } returns Optional.of(download)
        every { nzbImportRepository.findById(100L) } returns Optional.of(importRecord)

        coEvery { nzbStreamer.prepare(any()) } returns PrepareResult.MissingArticles(
            "missing", ArticleNotFoundException("missing")
        )

        every { usenetRepository.save(any<UsenetDownload>()) } answers { firstArg() }
        every { nzbImportRepository.save(any<NzbImportRecord>()) } answers { firstArg() }

        // when
        underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 1L, 100L))

        // then - nzbImportRepository.save is called: once for IMPORTING status (phase 1) + once in phase 3 = 2
        verify(exactly = 2) { nzbImportRepository.save(any<NzbImportRecord>()) }
        verify(exactly = 1) { usenetRepository.save(any<UsenetDownload>()) }
        assertEquals(UsenetDownloadStatus.FAILED, download.status)
        assertEquals(NzbImportStatus.FAILED, importRecord.status)
    }

    /**
     * Reproduces the production bug: ObjectOptimisticLockingFailureException when
     * the UsenetDownload row is deleted (e.g., via the SABnzbd delete API) while
     * the long-running NNTP prepare is in flight.
     *
     * Before the fix, executeImport held a single @Transactional spanning the entire
     * method including the NNTP I/O. If another transaction deleted the UsenetDownload
     * row during that I/O, the transaction commit would fail with:
     *   "Unexpected row count (expected row count 1 but was 0)
     *    [update usenet_download ... where id=?]"
     *
     * After the fix, executeImport uses short-lived TransactionTemplate scopes so no
     * transaction is held during I/O. The entity is re-fetched in the save phase; if
     * it was deleted, the method completes gracefully without throwing.
     */
    @Test
    fun `executeImport completes gracefully when UsenetDownload is deleted during NNTP IO`() {
        // given
        val download = createUsenetDownload(id = 102L)
        val importRecord = createImportRecord(id = 86L)

        // Phase 1 (load): download exists
        // Phase 3 (save): download has been deleted by another thread/request
        every { usenetRepository.findById(102L) } returnsMany listOf(
            Optional.of(download),
            Optional.empty()   // simulates concurrent deletion during NNTP I/O
        )
        every { nzbImportRepository.findById(86L) } returns Optional.of(importRecord)

        coEvery { nzbStreamer.prepare(any()) } returns PrepareResult.MissingArticles(
            "Article not found",
            ArticleNotFoundException("Article not found")
        )

        val importSlot = slot<NzbImportRecord>()
        every { nzbImportRepository.save(capture(importSlot)) } answers { importSlot.captured }

        // when — must NOT throw ObjectOptimisticLockingFailureException
        underTest.executeImport(NzbImportTaskData(nzbBytesBase64, 102L, 86L))

        // then
        // UsenetDownload.save is never called because the row is gone
        verify(exactly = 0) { usenetRepository.save(any<UsenetDownload>()) }
        // The import record is still saved so we have a record of the failure
        verify(exactly = 2) { nzbImportRepository.save(any<NzbImportRecord>()) }
        assertEquals(NzbImportStatus.FAILED, importSlot.captured.status)
        assertEquals("Download was deleted during import", importSlot.captured.errorMessage)
    }
}
