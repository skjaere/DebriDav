package io.skjaere.debridav.torrent.pgmq

import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.health.HealthCheckConfigurationProperties
import io.skjaere.debridav.health.HealthMetrics
import io.skjaere.debridav.health.HealthMetrics.HealthType
import io.skjaere.debridav.health.RepairAction
import io.skjaere.debridav.health.RepairOutcomeService
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TorrentHealthRepairHandler(
    private val torrentRepository: TorrentRepository,
    private val arrService: ArrService,
    private val fileService: DatabaseFileService,
    private val healthCheckConfig: HealthCheckConfigurationProperties,
    private val repairOutcomeService: RepairOutcomeService,
    private val healthMetrics: HealthMetrics,
) {
    private val logger = LoggerFactory.getLogger(TorrentHealthRepairHandler::class.java)

    @Transactional
    fun handle(msg: TorrentHealthRepairMessage, msgId: Long) {
        if (!healthCheckConfig.repairEnabled) {
            logger.debug("Repair is disabled, skipping torrent {}", msg.torrentId)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.SKIPPED)
            healthMetrics.recordRepair(HealthType.TORRENT, RepairAction.SKIPPED.name)
            return
        }

        val torrent = torrentRepository.findById(msg.torrentId).orElse(null)
        if (torrent == null) {
            logger.warn("No torrent found for ID {}", msg.torrentId)
            healthMetrics.recordRepair(HealthType.TORRENT, "NOT_FOUND")
            return
        }

        healthMetrics.timeRepair(HealthType.TORRENT) {
            executeRepair(msgId, torrent)
        }
    }

    private fun executeRepair(msgId: Long, torrent: Torrent) {
        val category = torrent.category?.name
        val hash = torrent.hash

        if (category != null && hash != null && arrService.getClientForCategory(category) != null) {
            logger.info(
                "Blocklisting torrent hash '{}' for '{}' (category: {})",
                hash, torrent.name, category
            )
            runBlocking { arrService.blocklist(hash, category) }

            var anyDeleted = false
            var anyRepaired = false
            torrent.files.forEach { file ->
                val fileName = file.name
                if (fileName != null) {
                    logger.info(
                        "Notifying Arr to delete file and search for '{}' (category: {})",
                        fileName, category
                    )
                    val found = runBlocking { arrService.deleteFileAndSearch(fileName, category) }
                    if (!found) {
                        logger.info(
                            "Arr could not find '{}', deleting from virtual filesystem",
                            fileName
                        )
                        fileService.deleteFile(file)
                        anyDeleted = true
                    } else {
                        anyRepaired = true
                    }
                }
            }
            val action = when {
                anyRepaired && !anyDeleted -> RepairAction.REPAIRED
                !anyRepaired && anyDeleted -> RepairAction.DELETED
                anyRepaired -> RepairAction.REPAIRED
                else -> RepairAction.DELETED
            }
            repairOutcomeService.record(QUEUE_NAME, msgId, action)
            healthMetrics.recordRepair(HealthType.TORRENT, action.name)
        } else {
            logger.info(
                "No Arr client for torrent {} (category: {}), deleting all files from virtual filesystem",
                torrent.id, category
            )
            torrent.files.forEach { file ->
                logger.info("Deleting '{}' from virtual filesystem", file.name)
                fileService.deleteFile(file)
            }
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.DELETED)
            healthMetrics.recordRepair(HealthType.TORRENT, RepairAction.DELETED.name)
        }
    }

    companion object {
        const val QUEUE_NAME = "torrent_health_repair"
    }
}
