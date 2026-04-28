package io.skjaere.debridav.torrent

import io.skjaere.debridav.repository.DebridFileContentsRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Periodically sweeps `Torrent` rows that have no entries in `torrent_files`. Such rows are
 * dead weight — there are no files referencing the magnet any more, so the row's purpose as
 * a per-magnet aggregate has expired.
 *
 * **Why a scheduled task and not an inline delete in
 * [io.skjaere.debridav.fs.DatabaseFileService.deleteRemotelyCachedEntity]?**
 *
 * The repair handler runs `fileService.deleteFile(...)` while still holding the health-check
 * handler's transaction open (it's about to save `torrent.lastVerified`). Doing the empty-
 * torrent prune inline would race that save and either rebuild the row via cascade-merge or
 * fail with optimistic-lock noise. Decoupling it onto a separate scheduled transaction
 * sidesteps both. The eventual-consistency window is small (default 10 min) and torrents in
 * the empty state aren't user-visible — qbit/SAB read paths filter to LIVE anyway.
 *
 * **Why not a JPA annotation?**
 *
 * JPA's `@OneToMany(orphanRemoval = true)` does the *opposite* — it deletes children when
 * they detach from a parent. There's no native annotation for "delete parent when its last
 * child is gone." The native-SQL sweep is the simplest expression of the rule.
 */
@Component
class EmptyTorrentCleanupTask(
    private val debridFileRepository: DebridFileContentsRepository,
) {
    private val logger = LoggerFactory.getLogger(EmptyTorrentCleanupTask::class.java)

    @Scheduled(fixedDelayString = "\${health-check.empty-torrent-cleanup-rate:PT10M}")
    @Transactional
    fun cleanupEmptyTorrents() {
        val deleted = debridFileRepository.deleteEmptyTorrents()
        if (deleted > 0) {
            logger.info("Pruned {} empty Torrent row(s)", deleted)
        }
    }
}
