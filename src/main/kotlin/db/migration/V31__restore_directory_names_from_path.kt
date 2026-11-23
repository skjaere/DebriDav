package db.migration

import io.ipfs.multibase.Base58
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Repairs directory names corrupted by two bugs in WebDAV directory MOVE handling.
 *
 * A directory's ltree `path` holds one Base58-encoded label per path segment, and lookups
 * resolve directories by that path. The `name` column is what listings show (and what
 * rclone uses to navigate). Releases before this one had two bugs that broke the link:
 *
 *   1. Renaming a directory in place set `name` on the directory *and on every subdirectory
 *      beneath it*. Renaming a series folder made all of its season folders show up under
 *      the series' new name, and opening them by that name returned 404.
 *   2. Moving a directory to a different parent under a new name stored the new `name` but
 *      kept the old last path label, so the listed name could not be opened.
 *
 * In both cases the path is intact and is what the rest of the system resolves against, so
 * it is treated as authoritative: wherever a directory's `name` differs from the decoded
 * last label of its `path`, `name` is reset to that label. For case 2 this means the
 * directory keeps its new location but shows its pre-move name again, which is the name
 * it is actually reachable at.
 *
 * The root directory (`ROOT`, name NULL) is skipped. Idempotent: rows whose name already
 * matches their path are not touched, so a re-run updates nothing.
 */
@Suppress("ClassName")
class V31__restore_directory_names_from_path : BaseJavaMigration() {

    private val logger = LoggerFactory.getLogger(V31__restore_directory_names_from_path::class.java)

    override fun migrate(context: Context) {
        val restored = restoreDirectoryNames(context.connection)
        logger.info("V31 complete: restored {} directory names from their paths", restored)
    }

    /** Resets every directory name that disagrees with its path. Returns the number of rows updated. */
    fun restoreDirectoryNames(conn: Connection): Int {
        val corrected = findMismatchedNames(conn)
        if (corrected.isEmpty()) return 0
        conn.prepareStatement("UPDATE db_item SET name = ? WHERE id = ?").use { update ->
            corrected.forEach { (id, name) ->
                update.setString(1, name)
                update.setLong(2, id)
                update.addBatch()
            }
            update.executeBatch()
        }
        return corrected.size
    }

    /** Maps directory id to the name decoded from its last path label, for rows where they differ. */
    private fun findMismatchedNames(conn: Connection): Map<Long, String> {
        val sql = """
            SELECT id, name, subpath(path, nlevel(path) - 1)::text AS label
            FROM db_item
            WHERE db_item_type = 'DbDirectory' AND path IS NOT NULL AND nlevel(path) > 1
        """.trimIndent()
        val corrected = mutableMapOf<Long, String>()
        conn.createStatement().use { stmt ->
            stmt.fetchSize = FETCH_SIZE
            stmt.executeQuery(sql).use { rows ->
                while (rows.next()) {
                    val id = rows.getLong("id")
                    val label = rows.getString("label")
                    val decoded = decodeLabel(label)
                    if (decoded == null) {
                        logger.warn(
                            "V31: directory {} has undecodable path label '{}'; leaving its name as is", id, label
                        )
                    } else if (decoded != rows.getString("name")) {
                        corrected[id] = decoded
                    }
                }
            }
        }
        return corrected
    }

    private fun decodeLabel(label: String): String? =
        runCatching { Base58.decode(label).decodeToString() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val FETCH_SIZE = 1000
    }
}
