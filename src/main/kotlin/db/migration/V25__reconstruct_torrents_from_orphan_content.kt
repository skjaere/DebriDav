package db.migration

import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.torrent.TorrentService
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Restores Torrent ↔ files relationships that prior releases hard-deleted, and backfills
 * `Torrent.magnet` for rows that pre-date V24's column addition.
 *
 * Every release before V24 hard-deleted Torrent rows on the qbit-emulation
 * `/torrents/delete` path. Sonarr/Radarr call that endpoint after a successful import,
 * which left every imported file as an orphan: its `RemotelyCachedEntity` row survived in
 * the FS but the `torrent_files` join entry and the `Torrent` row were both gone. The
 * magnet survived only because it was redundantly stored per-file on
 * `DebridCachedTorrentContent.magnet`.
 *
 * V24 added the `magnet` column to `Torrent` and switched `deleteTorrentByHash` to
 * soft-delete. This migration brings the historical data into the new model:
 *
 *   1. **Backfill** — for every existing `Torrent` row that doesn't have a magnet yet, pull
 *      one from any of its linked content rows.
 *   2. **Link orphan files** — for every file that has torrent content but no `torrent_files` entry,
 *      link it to the Torrent with its magnet's hash. A hash without a Torrent gets a fresh one
 *      with `status = DELETED` (= ordinal 1; we never observed it as LIVE so DELETED is the
 *      honest state) and `category = NULL` (the original category info was lost when the row
 *      was hard-deleted; the ArrService category-fanout from PR #29 makes that survivable).
 *
 * The hash is computed the way the app computes `Torrent.hash`, with
 * [TorrentService.getHashFromMagnet], so a file finds the Torrent the app created for it,
 * including base32 hashes and the `urn:btmh:` value the app stores for hybrid magnets whose
 * last `xt` is the v2 hash. Hashes are compared case-insensitively, as the app looks them up.
 * Files already in `torrent_files` (the join has `UNIQUE(files_id)`) and content rows no file
 * uses are left alone; files whose magnet has no hash are skipped and counted.
 *
 * Idempotent: a re-run finds no unlinked files with a hash and no Torrent to create.
 */
@Suppress("ClassName")
class V25__reconstruct_torrents_from_orphan_content : BaseJavaMigration() {

    private val logger = LoggerFactory.getLogger(V25__reconstruct_torrents_from_orphan_content::class.java)

    override fun migrate(context: Context) = reconstruct(context.connection)

    /** Public so tests can run it against seeded data after Flyway has applied V25. */
    fun reconstruct(conn: Connection) {
        val backfilled = backfillExistingTorrentMagnets(conn)
        val linked = linkOrphanFiles(conn)
        logger.info(
            "V25 complete: backfilled magnet on {} torrents, reconstructed {} from orphan content, " +
                    "created {} torrent_files join entries, skipped {} file(s) whose magnet has no info hash",
            backfilled, linked.reconstructed, linked.joined, linked.skipped,
        )
    }

    /** Step 1: copy magnet from a linked content row onto the Torrent for any Torrent that
     *  doesn't have one yet. Uses DISTINCT ON to pick a single content row per torrent. */
    private fun backfillExistingTorrentMagnets(conn: Connection): Int {
        val sql = """
            UPDATE torrent t
            SET magnet = sub.magnet
            FROM (
                SELECT DISTINCT ON (tf.torrent_id) tf.torrent_id, c.magnet
                FROM torrent_files tf
                JOIN db_item file ON file.id = tf.files_id
                JOIN debrid_cached_torrent_content c ON c.id = file.debrid_file_contents_id
                WHERE c.magnet IS NOT NULL
            ) sub
            WHERE t.id = sub.torrent_id AND t.magnet IS NULL
        """.trimIndent()
        return conn.createStatement().use { it.executeUpdate(sql) }
    }

    private data class LinkResult(val reconstructed: Int, val joined: Int, val skipped: Int)

    /** Step 2: link each file without a `torrent_files` entry to the Torrent with its hash. */
    private fun linkOrphanFiles(conn: Connection): LinkResult {
        val orphans = findUnlinkedFiles(conn)
        val torrentIdByHash = findTorrentIdsByHash(conn)
        var reconstructed = 0
        var skipped = 0
        val joins = mutableListOf<Pair<Long, Long>>()
        orphans.groupBy { (_, magnet) -> hashOf(magnet) }.forEach { (hash, files) ->
            if (hash == null) {
                skipped += files.size
                return@forEach
            }
            val torrentId = torrentIdByHash.getOrPut(hash.lowercase()) {
                reconstructed++
                insertDeletedTorrent(conn, hash, files.first().second)
            }
            files.forEach { (fileId, _) -> joins += torrentId to fileId }
        }
        return LinkResult(reconstructed, insertJoins(conn, joins), skipped)
    }

    /** (file id, magnet) of every file with torrent content that isn't in `torrent_files`. */
    private fun findUnlinkedFiles(conn: Connection): List<Pair<Long, String>> {
        val sql = """
            SELECT file.id, c.magnet
            FROM db_item file
            JOIN debrid_cached_torrent_content c ON c.id = file.debrid_file_contents_id
            WHERE file.db_item_type = 'RemotelyCachedEntity'
              AND c.magnet IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM torrent_files tf WHERE tf.files_id = file.id)
        """.trimIndent()
        val files = mutableListOf<Pair<Long, String>>()
        conn.createStatement().use { stmt ->
            stmt.fetchSize = FETCH_SIZE
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) files += rs.getLong(1) to rs.getString(2)
            }
        }
        return files
    }

    /** Lower-cased hash → torrent id. The lowest id wins for hashes that differ only in case. */
    private fun findTorrentIdsByHash(conn: Connection): MutableMap<String, Long> {
        val ids = mutableMapOf<String, Long>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT id, hash FROM torrent WHERE hash IS NOT NULL ORDER BY id").use { rs ->
                while (rs.next()) ids.putIfAbsent(rs.getString(2).lowercase(), rs.getLong(1))
            }
        }
        return ids
    }

    private fun hashOf(magnet: String): String? = runCatching {
        TorrentService.getHashFromMagnet(TorrentMagnet(magnet))?.hash
    }.getOrNull()?.takeIf { it.isNotBlank() }

    @Suppress("MagicNumber") // JDBC parameter indices
    private fun insertDeletedTorrent(conn: Connection, hash: String, magnet: String): Long {
        // status = 1 → Status.DELETED (the enum is ORDINAL-mapped; LIVE=0, DELETED=1).
        // save_path is required NOT NULL but operationally meaningless for a DELETED row;
        // we use the same convention createTorrent uses for live adds: "<downloadPath>/<name>".
        // `created` is a plain TIMESTAMP that Hibernate fills with UTC wall-clock time.
        val insert = """
            INSERT INTO torrent (id, status, name, hash, save_path, magnet, created)
            VALUES (nextval('torrent_seq'), 1, ?, ?, ?, ?, now() AT TIME ZONE 'UTC')
            RETURNING id
        """.trimIndent()
        val name = runCatching { TorrentService.getNameFromMagnet(TorrentMagnet(magnet)) }.getOrNull() ?: "unknown"
        return conn.prepareStatement(insert).use { stmt ->
            stmt.setString(1, name)
            stmt.setString(2, hash)
            stmt.setString(3, "/downloads/$name")
            stmt.setString(4, magnet)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    /** Inserts the (torrent id, file id) joins; ON CONFLICT is a backstop for UNIQUE(files_id). */
    private fun insertJoins(conn: Connection, joins: List<Pair<Long, Long>>): Int {
        if (joins.isEmpty()) return 0
        val sql = "INSERT INTO torrent_files (torrent_id, files_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        return conn.prepareStatement(sql).use { stmt ->
            joins.forEach { (torrentId, fileId) ->
                stmt.setLong(1, torrentId)
                stmt.setLong(2, fileId)
                stmt.addBatch()
            }
            stmt.executeBatch().count { it > 0 }
        }
    }

    private companion object {
        const val FETCH_SIZE = 1000
    }
}
