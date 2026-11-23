package io.skjaere.debridav.usenet.pgmq

import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.arrs.client.DeleteAndSearchResult
import io.skjaere.debridav.arrs.keptFileWarning
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.health.HealthCheckConfigurationProperties
import io.skjaere.debridav.health.HealthMetrics
import io.skjaere.debridav.health.HealthMetrics.HealthType
import io.skjaere.debridav.health.RepairAction
import io.skjaere.debridav.health.RepairOutcomeService
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NzbHealthRepairHandler(
    private val nzbDocumentRepository: NzbDocumentRepository,
    private val usenetRepository: UsenetRepository,
    private val arrService: ArrService,
    private val fileService: DatabaseFileService,
    private val healthCheckConfig: HealthCheckConfigurationProperties,
    private val repairOutcomeService: RepairOutcomeService,
    private val healthMetrics: HealthMetrics,
    private val debridavConfig: DebridavConfigurationProperties,
) {
    private val logger = LoggerFactory.getLogger(NzbHealthRepairHandler::class.java)

    @Transactional
    fun handle(msg: NzbHealthRepairMessage, msgId: Long) {
        if (!healthCheckConfig.repairEnabled) {
            logger.debug("Repair is disabled, skipping NZB document {}", msg.nzbDocumentId)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.SKIPPED)
            healthMetrics.recordRepair(HealthType.NZB, RepairAction.SKIPPED.name)
            return
        }

        val nzbDocument = nzbDocumentRepository.findById(msg.nzbDocumentId).orElse(null)
        if (nzbDocument == null) {
            logger.warn("No NzbDocument found for ID {}", msg.nzbDocumentId)
            healthMetrics.recordRepair(HealthType.NZB, "NOT_FOUND")
            return
        }

        healthMetrics.timeRepair(HealthType.NZB) {
            val action = executeRepair(msgId, nzbDocument)
            healthMetrics.recordRepair(HealthType.NZB, action.name)
        }
    }

    private fun executeRepair(
        msgId: Long,
        nzbDocument: NzbDocumentEntity
    ): RepairAction {
        val category = nzbDocument.category
        val name = nzbDocument.name
        if (name == null) {
            logger.warn("NzbDocument {} has no name, cannot notify Arr", nzbDocument.id)
            deleteVirtualFiles(nzbDocument.id!!)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.DELETED)
            return RepairAction.DELETED
        }

        val client = category?.let { arrService.getClientForCategory(it) }
        val action = when {
            // Targeted repair: known category, configured arr client.
            category != null && client != null -> executeTargetedRepair(nzbDocument, name, category)
            // Fan out: category null/unknown → try every configured arr.
            else -> executeFanoutRepair(nzbDocument, name)
        }
        repairOutcomeService.record(QUEUE_NAME, msgId, action)
        return action
    }

    private fun executeTargetedRepair(
        nzbDocument: NzbDocumentEntity,
        name: String,
        category: String,
    ): RepairAction {
        val downloadId = nzbDocument.downloadId
        if (downloadId != null) {
            logger.info("Blocklisting downloadId '{}' for '{}' (category: {})", downloadId, name, category)
            runBlocking { arrService.blocklist(downloadId, category) }
        } else {
            logger.warn("NzbDocument {} has no downloadId, skipping blocklist", nzbDocument.id)
        }
        val expectedPaths = expectedArrPathsFor(nzbDocument.id!!)
        logger.info(
            "Notifying Arr to delete file and search for '{}' (category: {}, expected paths: {})",
            name, category, expectedPaths,
        )
        val result = runBlocking { arrService.deleteFileAndSearch(name, expectedPaths, category) }
        return applyArrResult(nzbDocument, name, expectedPaths, result)
    }

    private fun executeFanoutRepair(
        nzbDocument: NzbDocumentEntity,
        name: String,
    ): RepairAction {
        logger.info(
            "NzbDocument {} has no known category client; fanning out blocklist + deleteFileAndSearch across all arrs",
            nzbDocument.id,
        )
        nzbDocument.downloadId?.let { runBlocking { arrService.blocklistAcrossAllArrs(it) } }
        val expectedPaths = expectedArrPathsFor(nzbDocument.id!!)
        val result = runBlocking { arrService.deleteFileAndSearchAcrossAllArrs(name, expectedPaths) }
        return applyArrResult(nzbDocument, name, expectedPaths, result)
    }

    /**
     * Deletes the NZB's files from the virtual filesystem only when no arr has them imported. A
     * file an arr has imported at another path is kept: with a wrong `debridav.mount-path` that
     * path is this very file. Files are also kept when an arr couldn't be asked.
     */
    private fun applyArrResult(
        nzbDocument: NzbDocumentEntity,
        name: String,
        expectedPaths: Set<String>,
        result: DeleteAndSearchResult,
    ): RepairAction = when (result) {
        DeleteAndSearchResult.Replaced -> RepairAction.REPAIRED
        DeleteAndSearchResult.NotImported -> {
            logger.info(
                "No arr has '{}' imported at expected paths {}, deleting NZB {} from virtual filesystem",
                name, expectedPaths, nzbDocument.id,
            )
            deleteVirtualFiles(nzbDocument.id!!)
            RepairAction.DELETED
        }

        is DeleteAndSearchResult.ImportedElsewhere, is DeleteAndSearchResult.Failed -> {
            logger.warn(keptFileWarning(name, result, expectedPaths, debridavConfig.mountPath))
            RepairAction.KEPT
        }
    }

    /**
     * The set of paths Sonarr/Radarr would see for the unhealthy NZB's files.
     * One entry per file in the NzbDocument's debridFiles. If the document
     * has no associated UsenetDownload (or no files), returns empty — the
     * arr clients treat that as an automatic no-match (don't touch anything).
     */
    private fun expectedArrPathsFor(nzbDocumentId: Long): Set<String> {
        val usenetDownload = usenetRepository.findByNzbDocumentId(nzbDocumentId) ?: return emptySet()
        val mountPath = debridavConfig.mountPath
        return usenetDownload.debridFiles.mapNotNull { file ->
            // Same caveat as the torrent path: `directory.path` is the LTree
            // (`ROOT.…` segments, base58-encoded). Use `fileSystemPath()` to get
            // the `/foo/bar` shape the arr actually reports back.
            val dirPath = file.directory?.fileSystemPath() ?: return@mapNotNull null
            val name = file.name ?: return@mapNotNull null
            "$mountPath$dirPath/$name"
        }.toSet()
    }

    private fun deleteVirtualFiles(nzbDocumentId: Long) {
        val usenetDownload = usenetRepository.findByNzbDocumentId(nzbDocumentId)
        if (usenetDownload != null) {
            usenetDownload.debridFiles.forEach { file ->
                logger.info("Deleting '{}' from virtual filesystem", file.name)
                fileService.deleteFile(file)
            }
        } else {
            logger.warn("No UsenetDownload found for NzbDocument {}, cannot delete virtual files", nzbDocumentId)
        }
    }

    companion object {
        const val QUEUE_NAME = "nzb_health_repair"
    }
}
