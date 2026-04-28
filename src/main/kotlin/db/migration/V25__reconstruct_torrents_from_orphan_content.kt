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
 *   2. **Reconstruct** — for every magnet hash that has files but no `Torrent` row, insert
 *      a fresh Torrent with `status = DELETED` (= ordinal 1; we never observed it as LIVE
 *      so DELETED is the honest state) and `category = NULL` (the original category info
 *      was lost when the row was hard-deleted; the ArrService category-fanout from PR #29
 *      makes that survivable).
 *   3. **Rebuild joins** — for every file whose magnet's hash matches a Torrent (existing
 *      or just-reconstructed) but isn't in `torrent_files` yet, insert the join row.
 *
 * Idempotent — `WHERE NOT EXISTS` clauses make every step a no-op on re-run, and the rows
 * we insert satisfy the `hash UNIQUE` constraint by deduplicating in step 2 before INSERT.
 */
@Suppress("ClassName")
class V25__reconstruct_torrents_from_orphan_content : BaseJavaMigration() {

    private val logger = LoggerFactory.getLogger(V25__reconstruct_torrents_from_orphan_content::class.java)

    override fun migrate(context: Context) {
        val conn = context.connection
        val backfilled = backfillExistingTorrentMagnets(conn)
        val reconstructed = reconstructMissingTorrents(conn)
        val joinsCreated = backfillTorrentFilesJoins(conn)
        logger.info(
            "V25 complete: backfilled magnet on {} torrents, reconstructed {} from orphan content, " +
                    "created {} torrent_files join entries",
            backfilled, reconstructed, joinsCreated,
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

    /** Step 2: reconstruct DELETED Torrents for magnet hashes that have files but no Torrent. */
    @Suppress("MagicNumber") // JDBC parameter indices
    private fun reconstructMissingTorrents(conn: Connection): Int {
        // Find one (magnet, sample-content-row) per distinct hash that doesn't already have a Torrent.
        val findOrphans = """
            SELECT DISTINCT ON (lower(regexp_replace(c.magnet, '.*xt=urn:btih:([0-9a-fA-F]+).*', '\1', 'i')))
                c.magnet AS magnet,
                lower(regexp_replace(c.magnet, '.*xt=urn:btih:([0-9a-fA-F]+).*', '\1', 'i')) AS hash
            FROM debrid_cached_torrent_content c
            WHERE c.magnet IS NOT NULL
              AND c.magnet ~* 'xt=urn:btih:[0-9a-fA-F]+'
              AND NOT EXISTS (
                  SELECT 1 FROM torrent t
                  WHERE lower(t.hash) =
                      lower(regexp_replace(c.magnet, '.*xt=urn:btih:([0-9a-fA-F]+).*', '\1', 'i'))
              )
        """.trimIndent()

        // (magnet, hash) tuples to insert.
        val orphans = mutableListOf<Pair<String, String>>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(findOrphans).use { rs ->
                while (rs.next()) {
                    orphans += rs.getString("magnet") to rs.getString("hash")
                }
            }
        }
        if (orphans.isEmpty()) return 0

        // status = 1 → Status.DELETED (the enum is ORDINAL-mapped; LIVE=0, DELETED=1).
        // save_path is required NOT NULL but operationally meaningless for a DELETED row;
        // we use the same convention createTorrent uses for live adds: "<downloadPath>/<name>".
        val insert = """
            INSERT INTO torrent (id, status, name, hash, save_path, magnet, created)
            VALUES (nextval('torrent_seq'), 1, ?, ?, ?, ?, NOW())
        """.trimIndent()
        var inserted = 0
        conn.prepareStatement(insert).use { stmt ->
            orphans.forEach { (magnet, hash) ->
                val name = TorrentService.getNameFromMagnet(TorrentMagnet(magnet)) ?: "unknown"
                stmt.setString(1, name)
                stmt.setString(2, hash)
                stmt.setString(3, "/downloads/$name")
                stmt.setString(4, magnet)
                stmt.executeUpdate()
                inserted++
            }
        }
        return inserted
    }

    /** Step 3: insert torrent_files entries linking each unlinked file to whichever Torrent
     *  (existing-LIVE, existing-DELETED, or just-reconstructed) carries its magnet's hash. */
    private fun backfillTorrentFilesJoins(conn: Connection): Int {
        val sql = """
            INSERT INTO torrent_files (torrent_id, files_id)
            SELECT t.id, file.id
            FROM db_item file
            JOIN debrid_cached_torrent_content c ON c.id = file.debrid_file_contents_id
            JOIN torrent t ON lower(t.hash) =
                lower(regexp_replace(c.magnet, '.*xt=urn:btih:([0-9a-fA-F]+).*', '\1', 'i'))
            WHERE file.db_item_type = 'RemotelyCachedEntity'
              AND c.magnet IS NOT NULL
              AND c.magnet ~* 'xt=urn:btih:[0-9a-fA-F]+'
              AND NOT EXISTS (
                  SELECT 1 FROM torrent_files tf
                  WHERE tf.torrent_id = t.id AND tf.files_id = file.id
              )
        """.trimIndent()
        return conn.createStatement().use { it.executeUpdate(sql) }
    }
}
