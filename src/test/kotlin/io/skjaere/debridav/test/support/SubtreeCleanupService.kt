package io.skjaere.debridav.test.support

import io.ipfs.multibase.Base58
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private const val ROOT_NODE = "ROOT"

/**
 * Test-only convenience over [SubtreeCleanupRepository] — wipes a directory subtree
 * (contents rows, file rows, and directory rows) in five native deletes. Callers
 * pass the human-readable path; we LTree-encode it the same way `DatabaseFileService`
 * does, but the encoding logic is small enough to duplicate here rather than
 * exporting a production extension.
 *
 * Used only by [io.skjaere.debridav.test.integrationtest.TorrentAddBurstIT] for fast
 * post-burst teardown — no production caller needs subtree-bulk-delete semantics.
 */
@Component
class SubtreeCleanupService(
    private val repository: SubtreeCleanupRepository,
) {
    @Transactional
    fun deleteDirectorySubtree(path: String) {
        val ltreePath = path.pathToLtree()
        repository.deleteTorrentContentsUnderDirectory(ltreePath)
        repository.deleteUsenetContentsUnderDirectory(ltreePath)
        repository.deleteNzbContentsUnderDirectory(ltreePath)
        repository.deleteFilesUnderDirectory(ltreePath)
        repository.deleteDirectorySubtreeRows(ltreePath)
    }

    private fun String.pathToLtree(): String {
        return if (this == "/") ROOT_NODE else {
            this.split("/").filter { it.isNotBlank() }
                .joinToString(separator = ".") { Base58.encode(it.encodeToByteArray()) }
                .let { "$ROOT_NODE.$it" }
        }
    }
}
