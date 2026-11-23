package io.skjaere.debridav.usenet

import io.skjaere.debridav.repository.NzbDocumentRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Periodically prunes `nzb_document` rows that no longer have any `nzb_contents`
 * referencing them. Mirrors [io.skjaere.debridav.torrent.EmptyTorrentCleanupTask] for
 * the usenet side.
 *
 * **Why orphans accumulate**: when a virtual file is deleted (by repair, by user, by
 * any other path through `DatabaseFileService.deleteFile`), only the `nzb_contents`
 * row goes away — the parent `nzb_document` stays put because nothing inline tears it
 * down. Without this sweep the orphan re-enters every health-check pass: its articles
 * verify as missing (real expiry on the source, since nothing's been streaming them),
 * the repair handler tries to delete already-deleted files, the arr re-imports a fresh
 * release, and the orphan loops forever.
 *
 * **Why a scheduled task and not an inline delete**: same reason as
 * [io.skjaere.debridav.torrent.EmptyTorrentCleanupTask] — running this inside the
 * file-deletion path would race with the health-check handler that's about to save
 * `lastVerified` on the same document. Decoupling onto a separate transaction
 * sidesteps the optimistic-lock noise.
 */
@Component
class EmptyNzbDocumentCleanupTask(
    private val nzbDocumentRepository: NzbDocumentRepository,
) {
    private val logger = LoggerFactory.getLogger(EmptyNzbDocumentCleanupTask::class.java)

    @Scheduled(fixedDelayString = "\${health-check.empty-nzb-cleanup-rate:PT10M}")
    @Transactional
    fun cleanupEmptyNzbDocuments() {
        val deleted = nzbDocumentRepository.deleteEmptyNzbDocuments()
        if (deleted > 0) {
            logger.info("Pruned {} empty NzbDocument row(s)", deleted)
        }
    }
}
