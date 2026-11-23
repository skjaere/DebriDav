package io.skjaere.debridav.test.integrationtest

import io.ipfs.multibase.Base58
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import org.flywaydb.core.Flyway
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

private const val UPGRADE_DB = "debridav_upgrade_it"
private const val LIVE = 0
private const val DELETED = 1
private const val FILE_SIZE = 1_000L

private const val HEX_LIVE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val HEX_ORPHAN = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val HEX_HYBRID = "cccccccccccccccccccccccccccccccccccccccc"
private const val HEX_UNUSED = "dddddddddddddddddddddddddddddddddddddddd"
private const val HEX_OLD_HYBRID = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
private const val HEX_CASE = "ffffffffffffffffffffffffffffffffffffffff"
private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private val BTMH = "1220" + "0".repeat(64)

/**
 * Upgrades a database with data in it, the way 1.0 meets a pre-1.0 install. Every other IT
 * migrates an empty database, so the data-changing migrations never see rows there.
 *
 * Migrates a separate database to V10 (0.11.0) and seeds the rows 0.11 left behind, including
 * the shapes that broke V25; migrates to V13 (the head of main) and seeds NZB documents, whose
 * tables appeared there; then migrates to the latest version and checks what V23, V24/V25, V30,
 * V31 and V32 did.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class UpgradeMigrationIT {

    @Autowired
    lateinit var appJdbc: JdbcTemplate

    @Value("\${spring.datasource.url}")
    lateinit var appUrl: String

    @Value("\${spring.datasource.username}")
    lateinit var username: String

    @Value("\${spring.datasource.password}")
    lateinit var password: String

    private lateinit var upgradeDb: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun createDatabase() {
        appJdbc.execute("DROP DATABASE IF EXISTS $UPGRADE_DB WITH (FORCE)")
        appJdbc.execute("CREATE DATABASE $UPGRADE_DB")
        upgradeDb = DriverManagerDataSource(appUrl.replaceAfterLast("/", UPGRADE_DB), username, password)
        jdbc = JdbcTemplate(upgradeDb)
    }

    @AfterEach
    fun dropDatabase() {
        appJdbc.execute("DROP DATABASE IF EXISTS $UPGRADE_DB WITH (FORCE)")
    }

    @Test
    fun `a 0_11 database with data upgrades to the latest schema`() {
        //given
        migrateTo("10")
        seed011()
        migrateTo("13")
        val nzbDocument = seedNzbDocument()

        //when
        val flyway = migrateTo(null)

        //then: every migration applied
        assertThat("pending migrations", flyway.info().pending().toList(), empty())
        assertThat(
            "schema version",
            flyway.info().current().version,
            `is`(flyway.info().all().last().version)
        )
        assertV23DroppedOnlyMismatchedCachedFiles()
        assertV25LinkedOrphansWithoutMergingOrAborting()
        assertV30MovedNzbSegments(nzbDocument)
        assertV31RestoredDirectoryNames()
        assertV32SpreadFirstHealthChecks()
    }

    private fun assertV23DroppedOnlyMismatchedCachedFiles() {
        val links = jdbc.queryForList(
            "SELECT elem->>'@type' AS type, elem->>'size' AS size " +
                    "FROM debrid_cached_torrent_content c, jsonb_array_elements(c.debrid_links) elem WHERE c.id = 201"
        ).map { "${it["type"]}:${it["size"]}" }
        assertThat("content 201 links", links, containsInAnyOrder("CachedFile:$FILE_SIZE", "MissingFile:null"))
    }

    private fun assertV25LinkedOrphansWithoutMergingOrAborting() {
        assertThat("magnet backfilled", torrentColumn("magnet", HEX_LIVE), `is`(magnet(HEX_LIVE)))
        assertThat("live torrent's file", linkedHashes(101), contains(HEX_LIVE))
        assertThat("orphan's torrent status", torrentColumn("status", HEX_ORPHAN), `is`(DELETED.toString()))
        assertThat("orphan's torrent", linkedHashes(102), contains(HEX_ORPHAN))
        assertThat("hybrid orphan's torrent", linkedHashes(103), contains(HEX_HYBRID))
        assertThat("base32 orphan's torrent", linkedHashes(104), empty())
        assertThat("old hybrid torrent's file", linkedHashes(106), contains("urn:btmh:$BTMH"))
        assertThat("case-variant orphan's torrents", linkedHashes(107).map { it.lowercase() }, contains(HEX_CASE))
        assertThat(
            "torrents for base32, unused content and the old hybrid's btih",
            jdbc.queryForList(
                "SELECT hash FROM torrent WHERE lower(hash) LIKE 'abcdef%' OR hash IN (?, ?)",
                String::class.java, HEX_UNUSED, HEX_OLD_HYBRID
            ),
            empty()
        )
    }

    private fun assertV30MovedNzbSegments(nzbDocument: Long) {
        val files = jdbc.queryForList(
            "SELECT file_index, yenc_size, yenc_part_end FROM nzb_file WHERE nzb_document_id = ? ORDER BY file_index",
            nzbDocument
        ).map { "${it["file_index"]}:${it["yenc_size"]}:${it["yenc_part_end"]}" }
        assertThat("nzb files", files, contains("0:100:50", "1:200:null"))
        val segments = jdbc.queryForList(
            "SELECT f.file_index, s.segment_number, s.article_id, s.bytes FROM nzb_segment s " +
                    "JOIN nzb_file f ON f.id = s.nzb_file_id WHERE f.nzb_document_id = ? " +
                    "ORDER BY f.file_index, s.segment_number",
            nzbDocument
        ).map { "${it["file_index"]}:${it["segment_number"]}:${it["article_id"]}:${it["bytes"]}" }
        assertThat("nzb segments", segments, contains("0:1:<a1@x>:50", "0:2:<a2@x>:50", "1:1:<b1@x>:200"))
        assertThat(
            "nzb_document.files column",
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                        "WHERE table_name = 'nzb_document' AND column_name = 'files'",
                Int::class.java
            ),
            `is`(0)
        )
    }

    private fun assertV31RestoredDirectoryNames() {
        assertThat("damaged season directory", directoryName(5), `is`("Season 01"))
        assertThat("show directory", directoryName(4), `is`("Show"))
    }

    private fun assertV32SpreadFirstHealthChecks() {
        // Compared in UTC, as Hibernate reads the column; see V32.
        assertThat(
            "torrents not due within the next day",
            jdbc.queryForObject(
                "SELECT count(*) FROM torrent WHERE last_verified IS NULL " +
                        "OR last_verified NOT BETWEEN (now() AT TIME ZONE 'UTC') - interval '1 day' " +
                        "AND (now() AT TIME ZONE 'UTC')",
                Int::class.java
            ),
            `is`(0)
        )
    }

    /** The rows 0.11 leaves behind: directories, files, content, torrents and their joins. */
    private fun seed011() {
        directory(1, "/", null)
        directory(2, "/downloads", 1)
        directory(3, "/tv", 1)
        directory(4, "/tv/Show", 3)
        // The pre-1.0 directory rename bug gave every subdirectory its parent's new name.
        directory(5, "/tv/Show/Season 01", 4, name = "Show")

        content(
            201, magnet(HEX_LIVE),
            """[{"@type": "CachedFile", "size": $FILE_SIZE, "provider": "PREMIUMIZE"},
                {"@type": "CachedFile", "size": 999, "provider": "REAL_DEBRID"},
                {"@type": "MissingFile", "provider": "TORBOX"}]"""
        )
        content(202, magnet(HEX_ORPHAN))
        content(203, "magnet:?xt=urn:btih:$HEX_HYBRID&xt=urn:btmh:$BTMH&dn=hybrid")
        content(204, "magnet:?xt=urn:btih:$BASE32&dn=base32")
        content(205, magnet(HEX_UNUSED))
        content(206, "magnet:?xt=urn:btih:$HEX_OLD_HYBRID&xt=urn:btmh:$BTMH&dn=old-hybrid")
        content(207, magnet(HEX_CASE))

        file(101, 5, "Show - S01E01.mkv", 201)
        file(102, 2, "orphan.mkv", 202)
        file(103, 2, "hybrid.mkv", 203)
        file(104, 2, "base32.mkv", 204)
        file(106, 2, "old-hybrid.mkv", 206)
        file(107, 2, "case.mkv", 207)

        torrent(301, HEX_LIVE, LIVE, 101)
        // An older release stored a hybrid magnet's last xt, the v2 hash, as the torrent hash.
        torrent(306, "urn:btmh:$BTMH", LIVE, 106)
        // torrent.hash is unique but case-sensitive.
        torrent(307, HEX_CASE.uppercase(), DELETED)
        torrent(308, HEX_CASE, DELETED)
    }

    private fun seedNzbDocument(): Long = jdbc.queryForObject(
        "INSERT INTO nzb_document (files, name) VALUES (?::jsonb, 'release') RETURNING id",
        Long::class.java,
        """[{"yencSize": 100, "yencPartEnd": 50, "segments": [
                {"number": 1, "articleId": "<a1@x>", "bytes": 50},
                {"number": 2, "articleId": "<a2@x>", "bytes": 50}]},
            {"yencSize": 200, "segments": [{"number": 1, "articleId": "<b1@x>", "bytes": 200}]}]"""
    )!!

    private fun migrateTo(target: String?): Flyway = Flyway.configure()
        .dataSource(upgradeDb)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(target) }
        .load()
        .also { it.migrate() }

    private fun directory(id: Long, path: String, parentId: Long?, name: String? = path.substringAfterLast("/")) {
        val ltree = if (path == "/") "ROOT" else
            "ROOT." + path.trim('/').split("/").joinToString(".") { Base58.encode(it.encodeToByteArray()) }
        jdbc.update(
            "INSERT INTO db_item (db_item_type, id, name, path, directory_id) " +
                    "VALUES ('DbDirectory', ?, ?, ?::ltree, ?)",
            id, if (path == "/") null else name, ltree, parentId
        )
    }

    private fun content(id: Long, magnet: String, links: String = "[]") {
        jdbc.update(
            "INSERT INTO debrid_cached_torrent_content " +
                    "(id, debrid_links, mime_type, modified, original_path, size, magnet) " +
                    "VALUES (?, ?::jsonb, 'video/x-matroska', 0, ?, ?, ?)",
            id, links, "content-$id.mkv", FILE_SIZE, magnet
        )
    }

    private fun file(id: Long, directoryId: Long, name: String, contentId: Long) {
        jdbc.update(
            "INSERT INTO db_item (db_item_type, id, name, size, directory_id, debrid_file_contents_id) " +
                    "VALUES ('RemotelyCachedEntity', ?, ?, ?, ?, ?)",
            id, name, FILE_SIZE, directoryId, contentId
        )
    }

    private fun torrent(id: Long, hash: String, status: Int, vararg fileIds: Long) {
        jdbc.update(
            "INSERT INTO torrent (id, created, hash, name, save_path, status) VALUES (?, now(), ?, ?, '/downloads', ?)",
            id, hash, "torrent-$id", status
        )
        fileIds.forEach { jdbc.update("INSERT INTO torrent_files (torrent_id, files_id) VALUES (?, ?)", id, it) }
    }

    private fun magnet(hash: String) = "magnet:?xt=urn:btih:$hash&dn=release-$hash"

    private fun torrentColumn(column: String, hash: String): String? =
        jdbc.queryForObject("SELECT $column::text FROM torrent WHERE hash = ?", String::class.java, hash)

    private fun linkedHashes(fileId: Long): List<String> = jdbc.queryForList(
        "SELECT t.hash FROM torrent_files tf JOIN torrent t ON t.id = tf.torrent_id WHERE tf.files_id = ?",
        String::class.java, fileId
    ).filterNotNull()

    private fun directoryName(id: Long): String? =
        jdbc.queryForObject("SELECT name FROM db_item WHERE id = ?", String::class.java, id)
}
