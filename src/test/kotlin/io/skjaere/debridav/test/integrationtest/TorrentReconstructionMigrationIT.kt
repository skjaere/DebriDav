package io.skjaere.debridav.test.integrationtest

import db.migration.V25__reconstruct_torrents_from_orphan_content
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.torrent.Status
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import javax.sql.DataSource

private const val HEX_A = "1111111111111111111111111111111111111111"
private const val HEX_B = "2222222222222222222222222222222222222222"
private const val HEX_C = "3333333333333333333333333333333333333333"

/**
 * Exercises V25 against the pre-1.0 states that broke it. Flyway has already run V25 on the (empty)
 * test database at startup, so each test seeds its rows and runs the migration again directly, all
 * in a transaction that is rolled back. Files other ITs left behind get linked too, so assertions
 * look only at each test's own hashes.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class TorrentReconstructionMigrationIT {

    @Autowired
    lateinit var databaseFileService: DatabaseFileService

    @Autowired
    lateinit var torrentRepository: TorrentRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Test
    fun `a file already linked to a torrent whose hash the app stored as urn-btmh is left alone`() = rolledBack {
        //given: a hybrid magnet with btmh last, for which the app stores the hash as "urn:btmh:..."
        val magnet = "magnet:?xt=urn:btih:$HEX_A&xt=urn:btmh:1220${"a".repeat(64)}&dn=hybrid"
        val file = file("/downloads/hybrid/hybrid.mkv", magnet)
        torrent("urn:btmh:1220${"a".repeat(64)}", Status.LIVE, file)

        //when
        runV25()

        //then
        // no second torrent from the magnet's btih hash
        assertThat(
            torrentsWithHashes("urn:btmh:1220${"a".repeat(64)}", HEX_A),
            containsInAnyOrder("urn:btmh:1220${"a".repeat(64)}")
        )
        assertThat(torrentHashOf(file), `is`("urn:btmh:1220${"a".repeat(64)}"))
    }

    @Test
    fun `an orphan file is linked once when two torrents' hashes differ only in case`() = rolledBack {
        //given
        torrent(HEX_B.replace('2', 'B').uppercase(), Status.DELETED)
        torrent(HEX_B.replace('2', 'b'), Status.DELETED)
        val orphan = file("/downloads/case/case.mkv", "magnet:?xt=urn:btih:${HEX_B.replace('2', 'b')}&dn=case")

        //when
        runV25()

        //then
        assertThat(torrentHashOf(orphan)?.lowercase(), `is`(HEX_B.replace('2', 'b')))
    }

    @Test
    fun `orphans with base32 hashes are skipped, not merged into one torrent`() = rolledBack {
        //given: the old regex read only the leading hex characters, "ABCDEF" for both. The app's
        // TorrentHash takes 40-character hashes only, so it can't create a torrent for either.
        val first = file("/downloads/b32a/a.mkv", "magnet:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&dn=first")
        val second = file("/downloads/b32b/b.mkv", "magnet:?xt=urn:btih:ABCDEFZYXWVUTSRQPONMLKJIHG765432&dn=second")

        //when
        runV25()

        //then
        assertThat(torrentHashes().filter { it?.lowercase()?.startsWith("abcdef") == true }, empty())
        assertThat(torrentHashOf(first), nullValue())
        assertThat(torrentHashOf(second), nullValue())
    }

    @Test
    fun `content no file uses gets no torrent`() = rolledBack {
        //given: the file was deleted, its content row was left behind
        val gone = file("/downloads/gone/gone.mkv", "magnet:?xt=urn:btih:$HEX_C&dn=gone")
        entityManager.flush()
        assertThat("db_item rows deleted", jdbcTemplate.update("DELETE FROM db_item WHERE id = ?", gone.id), `is`(1))

        //when
        runV25()

        //then
        assertThat(torrentsWithHashes(HEX_C), empty())
    }

    @Test
    fun `an orphan file gets a deleted torrent, or joins the torrent that has its hash`() = rolledBack {
        //given
        val orphan = file("/downloads/orphan/orphan.mkv", "magnet:?xt=urn:btih:$HEX_A&dn=Orphan.Release")
        torrent(HEX_C, Status.LIVE)
        val ofLiveTorrent = file("/downloads/live/live.mkv", "magnet:?xt=urn:btih:${HEX_C.uppercase()}&dn=live")

        //when
        runV25()

        //then
        assertThat(torrentsWithHashes(HEX_A, HEX_C), containsInAnyOrder(HEX_A, HEX_C))
        assertThat(torrentHashOf(orphan), `is`(HEX_A))
        assertThat(
            jdbcTemplate.queryForObject("SELECT status FROM torrent WHERE hash = ?", Int::class.java, HEX_A),
            `is`(Status.DELETED.ordinal)
        )
        assertThat(torrentHashOf(ofLiveTorrent), `is`(HEX_C))
    }

    private fun rolledBack(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { tx ->
            tx.setRollbackOnly()
            jdbcTemplate.update("DELETE FROM torrent_files")
            jdbcTemplate.update("DELETE FROM torrent")
            block()
        }
    }

    private fun runV25() {
        entityManager.flush()
        V25__reconstruct_torrents_from_orphan_content().reconstruct(DataSourceUtils.getConnection(dataSource))
        entityManager.clear()
    }

    private fun file(path: String, magnet: String): RemotelyCachedEntity {
        val contents = DebridCachedTorrentContent(
            originalPath = path.substringAfterLast("/"),
            size = 1_000L,
            modified = Instant.EPOCH.toEpochMilli(),
            magnet = magnet,
            mimeType = "video/x-matroska",
            debridLinks = mutableListOf(MissingFile(DebridProvider.PREMIUMIZE, Instant.EPOCH.toEpochMilli()))
        )
        val file = databaseFileService.createDebridFile(path, "unused", contents)
        databaseFileService.saveDbEntity(file)
        return file
    }

    private fun torrent(hash: String, status: Status, vararg files: RemotelyCachedEntity) {
        torrentRepository.save(Torrent().apply {
            name = hash.take(12)
            this.hash = hash
            savePath = "/downloads"
            this.status = status
            this.files = files.toMutableList()
        })
    }

    /** The torrents among [hashes], ignoring case. Other ITs leave files the migration also links. */
    private fun torrentsWithHashes(vararg hashes: String): List<String?> =
        torrentHashes().filter { hash -> hashes.any { it.equals(hash, ignoreCase = true) } }

    private fun torrentHashes(): List<String?> =
        jdbcTemplate.queryForList("SELECT hash FROM torrent", String::class.java)

    private fun torrentHashOf(file: RemotelyCachedEntity): String? = jdbcTemplate.queryForList(
        "SELECT t.hash FROM torrent_files tf JOIN torrent t ON t.id = tf.torrent_id WHERE tf.files_id = ?",
        String::class.java, file.id
    ).also { assertThat("torrents linked to file ${file.id}", it.size <= 1, `is`(true)) }.singleOrNull()
}
