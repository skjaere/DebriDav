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
        val client = category?.let { arrService.getClientForCategory(it) }

        // Three branches:
        //   1. We know the category and have a client → targeted blocklist + targeted delete-and-search
        //   2. No client (unknown / NULL category, e.g. reconstructed torrent) → fan out across every
        //      configured arr. Non-matching arrs silently no-op; matching arr handles it.
        //   3. No arrs configured at all → only path is to drop files from the VFS.
        val action = when {
            category != null && hash != null && client != null ->
                executeKnownCategoryRepair(torrent, hash, category)

            hash != null ->
                executeFanoutRepair(torrent, hash)

            else -> {
                logger.info("Torrent {} has no hash, deleting files from virtual filesystem", torrent.id)
                torrent.files.forEach { fileService.deleteFile(it) }
                RepairAction.DELETED
            }
        }
        repairOutcomeService.record(QUEUE_NAME, msgId, action)
        healthMetrics.recordRepair(HealthType.TORRENT, action.name)
    }

    private fun executeKnownCategoryRepair(torrent: Torrent, hash: String, category: String): RepairAction {
        logger.info("Blocklisting torrent hash '{}' for '{}' (category: {})", hash, torrent.name, category)
        runBlocking { arrService.blocklist(hash, category) }

        var anyRepaired = false
        torrent.files.forEach { file ->
            val fileName = file.name ?: return@forEach
            logger.info("Notifying Arr to delete file and search for '{}' (category: {})", fileName, category)
            val found = runBlocking { arrService.deleteFileAndSearch(fileName, category) }
            if (!found) {
                logger.info("Arr could not find '{}', deleting from virtual filesystem", fileName)
                fileService.deleteFile(file)
            } else {
                anyRepaired = true
            }
        }
        return if (anyRepaired) RepairAction.REPAIRED else RepairAction.DELETED
    }

    private fun executeFanoutRepair(torrent: Torrent, hash: String): RepairAction {
        logger.info(
            "Torrent {} has no known category client; fanning out blocklist + deleteFileAndSearch across all arrs",
            torrent.id,
        )
        runBlocking { arrService.blocklistAcrossAllArrs(hash) }

        var anyRepaired = false
        torrent.files.forEach { file ->
            val fileName = file.name ?: return@forEach
            val handled = runBlocking { arrService.deleteFileAndSearchAcrossAllArrs(fileName) }
            if (handled) {
                anyRepaired = true
            } else {
                logger.info("No arr recognised '{}', deleting from virtual filesystem", fileName)
                fileService.deleteFile(file)
            }
        }
        return if (anyRepaired) RepairAction.REPAIRED else RepairAction.DELETED
    }

    companion object {
        const val QUEUE_NAME = "torrent_health_repair"
    }
}
