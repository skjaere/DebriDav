package io.skjaere.debridav.torrent.pgmq

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
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.torrent.TorrentRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class TorrentHealthRepairHandler(
    private val torrentRepository: TorrentRepository,
    private val arrService: ArrService,
    private val fileService: DatabaseFileService,
    private val healthCheckConfig: HealthCheckConfigurationProperties,
    private val repairOutcomeService: RepairOutcomeService,
    private val healthMetrics: HealthMetrics,
    private val debridFileContentsRepository: DebridFileContentsRepository,
    private val debridavConfig: DebridavConfigurationProperties,
    platformTransactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(platformTransactionManager)
    private val logger = LoggerFactory.getLogger(TorrentHealthRepairHandler::class.java)

    /**
     * Three-phase repair: short read tx → blocking arr HTTP calls (no tx) → short write tx
     * per file. Replaces the previous single `@Transactional` that held a DB tx open across
     * the multi-second `runBlocking { arrService.* }` calls — that pattern caused
     * `StaleStateException` at commit when concurrent operations (other repair workers,
     * arr webhooks deleting files via the WebDAV layer, scheduled cleanup tasks) deleted
     * a `debrid_cached_torrent_content` row mid-handler. Splitting the tx boundaries
     * removes the race window. See also the matching no-inline-cleanup comment in
     * [DatabaseFileService.deleteRemotelyCachedEntity].
     */
    fun handle(msg: TorrentHealthRepairMessage, msgId: Long) {
        if (!healthCheckConfig.repairEnabled) {
            logger.debug("Repair is disabled, skipping torrent {}", msg.torrentId)
            repairOutcomeService.record(QUEUE_NAME, msgId, RepairAction.SKIPPED)
            healthMetrics.recordRepair(HealthType.TORRENT, RepairAction.SKIPPED.name)
            return
        }

        val ctx = loadRepairContext(msg.torrentId)
        if (ctx == null) {
            logger.warn("No torrent found for ID {}", msg.torrentId)
            healthMetrics.recordRepair(HealthType.TORRENT, "NOT_FOUND")
            return
        }

        healthMetrics.timeRepair(HealthType.TORRENT) {
            val action = executeRepair(ctx)
            repairOutcomeService.record(QUEUE_NAME, msgId, action)
            healthMetrics.recordRepair(HealthType.TORRENT, action.name)
        }
    }

    /**
     * Phase 1. Snapshot the repair-relevant fields (hash, category name, file id+name)
     * inside a short read tx, then close it. The returned [RepairContext] is plain
     * data — no JPA proxies survive past this point, so subsequent code can run
     * outside any persistence context.
     */
    private fun loadRepairContext(torrentId: Long): RepairContext? {
        return transactionTemplate.execute { _ ->
            val torrent = torrentRepository.findById(torrentId).orElse(null) ?: return@execute null
            RepairContext(
                torrentId = torrentId,
                hash = torrent.hash,
                category = torrent.category?.name,
                files = torrent.files.mapNotNull { f ->
                    val id = f.id ?: return@mapNotNull null
                    val name = f.name ?: return@mapNotNull null
                    // `directory.path` is the raw LTree string (e.g.
                    // `ROOT.2H9aS71LC814W.KWff…`) — base58-encoded segment names. To
                    // build the path Sonarr/Radarr would see we have to decode through
                    // `fileSystemPath()`, which splits on `.`, drops the `ROOT` head,
                    // and Base58-decodes each remaining segment. Using `.path` directly
                    // produced an unmatchable `/data/ROOT.<garbled>/<file>` and every
                    // repair silently fell through to delete-only.
                    val dirPath = f.directory?.fileSystemPath() ?: return@mapNotNull null
                    RepairFile(id = id, name = name, expectedArrPath = arrPathFor(dirPath, name))
                }
            )
        }
    }

    /**
     * The path Sonarr/Radarr would see for a debridav-served file: the debridav
     * mount-path (where the WebDAV mount appears in the arr's filesystem)
     * concatenated with the file's local path inside debridav. The arr clients
     * compare this to the `path` they get back from `/parse` to verify they're
     * acting on the same physical file.
     */
    private fun arrPathFor(directoryPath: String, fileName: String): String =
        "${debridavConfig.mountPath}$directoryPath/$fileName"

    private fun executeRepair(ctx: RepairContext): RepairAction {
        val client = ctx.category?.let { arrService.getClientForCategory(it) }

        // Three branches:
        //   1. We know the category and have a client → targeted blocklist + targeted delete-and-search
        //   2. No client (unknown / NULL category, e.g. reconstructed torrent) → fan out across every
        //      configured arr. Non-matching arrs silently no-op; matching arr handles it.
        //   3. No arrs configured at all → only path is to drop files from the VFS.
        return when {
            ctx.category != null && ctx.hash != null && client != null ->
                executeKnownCategoryRepair(ctx)

            ctx.hash != null ->
                executeFanoutRepair(ctx)

            else -> {
                logger.info("Torrent {} has no hash, deleting files from virtual filesystem", ctx.torrentId)
                deleteFilesById(ctx.files.map { it.id })
                RepairAction.DELETED
            }
        }
    }

    private fun executeKnownCategoryRepair(ctx: RepairContext): RepairAction {
        val hash = requireNotNull(ctx.hash)
        val category = requireNotNull(ctx.category)
        logger.info("Blocklisting torrent hash '{}' for torrent {} (category: {})", hash, ctx.torrentId, category)
        // Phase 2: arr HTTP calls — no DB tx held across these.
        runBlocking { arrService.blocklist(hash, category) }
        return replaceInArrsOrDelete(ctx.files) { rf ->
            logger.info(
                "Notifying Arr to delete file and search for '{}' (category: {}, expected path: {})",
                rf.name, category, rf.expectedArrPath,
            )
            runBlocking { arrService.deleteFileAndSearch(rf.name, setOf(rf.expectedArrPath), category) }
        }
    }

    private fun executeFanoutRepair(ctx: RepairContext): RepairAction {
        val hash = requireNotNull(ctx.hash)
        logger.info(
            "Torrent {} has no known category client; fanning out blocklist + deleteFileAndSearch across all arrs",
            ctx.torrentId,
        )
        runBlocking { arrService.blocklistAcrossAllArrs(hash) }
        return replaceInArrsOrDelete(ctx.files) { rf ->
            runBlocking { arrService.deleteFileAndSearchAcrossAllArrs(rf.name, setOf(rf.expectedArrPath)) }
        }
    }

    /**
     * Asks the arrs to replace each file, then (phase 3) deletes from the virtual filesystem only
     * the files no arr has imported. A file an arr has imported at another path is kept: with a
     * wrong `debridav.mount-path` that path is this very file, and deleting it would remove it from
     * the arr's library without a new search. Files are also kept when an arr couldn't be asked.
     */
    private fun replaceInArrsOrDelete(
        files: List<RepairFile>,
        askArrs: (RepairFile) -> DeleteAndSearchResult,
    ): RepairAction {
        val results = files.map { rf -> rf to askArrs(rf) }
        results.forEach { (rf, result) ->
            when (result) {
                DeleteAndSearchResult.Replaced -> Unit
                DeleteAndSearchResult.NotImported -> logger.info(
                    "No arr has '{}' imported at expected path '{}', will delete from virtual filesystem",
                    rf.name, rf.expectedArrPath,
                )
                is DeleteAndSearchResult.ImportedElsewhere, is DeleteAndSearchResult.Failed -> logger.warn(
                    keptFileWarning(rf.name, result, setOf(rf.expectedArrPath), debridavConfig.mountPath)
                )
            }
        }
        deleteFilesById(results.filter { it.second == DeleteAndSearchResult.NotImported }.map { it.first.id })
        return when {
            results.any { it.second == DeleteAndSearchResult.Replaced } -> RepairAction.REPAIRED
            results.any { it.second != DeleteAndSearchResult.NotImported } -> RepairAction.KEPT
            else -> RepairAction.DELETED
        }
    }

    /**
     * Phase 3. One short tx per file: re-load by id, delete if still present, skip if
     * already gone. Re-loading per-tx (instead of carrying the JPA entity from phase 1)
     * means concurrent deletions by arr-webhook traffic or scheduled cleanup show up
     * cleanly as `findById = empty` rather than as `StaleStateException` at commit.
     */
    private fun deleteFilesById(fileIds: List<Long>) {
        if (fileIds.isEmpty()) return
        fileIds.forEach { id ->
            transactionTemplate.execute { _ ->
                debridFileContentsRepository.findById(id).orElse(null)?.let { file ->
                    fileService.deleteFile(file)
                } ?: logger.debug("File {} already gone — skipping delete", id)
            }
        }
    }

    private data class RepairContext(
        val torrentId: Long,
        val hash: String?,
        val category: String?,
        val files: List<RepairFile>
    )

    private data class RepairFile(val id: Long, val name: String, val expectedArrPath: String)

    companion object {
        const val QUEUE_NAME = "torrent_health_repair"
    }
}
