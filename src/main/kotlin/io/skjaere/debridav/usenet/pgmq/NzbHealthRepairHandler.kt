package io.skjaere.debridav.usenet.pgmq

import io.skjaere.debridav.arrs.ArrService
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
        if (category == null || name == null) {
            logger.warn(
                "NzbDocument {} missing category or name, cannot notify Arr",
                nzbDocument.id
            )
            deleteVirtualFiles(nzbDocument.id!!)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.DELETED)
            return RepairAction.DELETED
        }

        return if (arrService.getClientForCategory(category) != null) {
            val downloadId = nzbDocument.downloadId
            if (downloadId != null) {
                logger.info(
                    "Blocklisting downloadId '{}' for '{}' (category: {})",
                    downloadId, name, category
                )
                runBlocking { arrService.blocklist(downloadId, category) }
            } else {
                logger.warn(
                    "NzbDocument {} has no downloadId, skipping blocklist",
                    nzbDocument.id
                )
            }

            logger.info(
                "Notifying Arr to delete file and search for '{}' (category: {})",
                name, category
            )
            val found = runBlocking { arrService.deleteFileAndSearch(name, category) }
            val action = if (!found) {
                logger.info(
                    "Arr could not find '{}', deleting from virtual filesystem",
                    name
                )
                deleteVirtualFiles(nzbDocument.id!!)
                RepairAction.DELETED
            } else {
                RepairAction.REPAIRED
            }
            repairOutcomeService.record(QUEUE_NAME, msgId, action)
            action
        } else {
            logger.info(
                "No Arr client for NZB {} (category: {}), deleting files from virtual filesystem",
                nzbDocument.id, category
            )
            deleteVirtualFiles(nzbDocument.id!!)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.DELETED)
            RepairAction.DELETED
        }
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
