package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import com.github.sardine.impl.SardineException
import db.migration.V31__restore_directory_names_from_path
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import javax.sql.DataSource
import kotlin.test.fail
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

/**
 * Exercises the V31 repair against directory names corrupted the way the pre-1.0 directory
 * MOVE bugs left them. Flyway has already run V31 on the (empty) test database at startup,
 * so the damage is seeded afterwards and the repair is invoked directly.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class DirectoryNameRepairMigrationIT {

    @Autowired
    lateinit var dataSource: DataSource

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    @Test
    fun thatV31RestoresDirectoryNamesFromTheirPaths() {
        //given
        sardine.createDirectory(url("/repairSeries"))
        sardine.createDirectory(url("/repairSeries/repairSeason01"))
        sardine.createDirectory(url("/repairSeries/repairSeason02"))
        sardine.createDirectory(url("/repairLibrary"))
        sardine.createDirectory(url("/repairLibrary/repairRelease"))

        try {
            // bug 1: an in-place rename of the parent overwrote every subdirectory's name
            corruptDirectoryName(from = "repairSeason01", to = "repairSeries")
            corruptDirectoryName(from = "repairSeason02", to = "repairSeries")
            // bug 2: a move + rename stored the new name but kept the old path label
            corruptDirectoryName(from = "repairRelease", to = "repairRenamedRelease")
            assertThat(
                listDirectory("/repairSeries"), not(
                    hasItem<DavResource>(hasProperty("displayName", `is`("repairSeason01")))
                )
            )

            //when
            val restored = dataSource.connection.use {
                V31__restore_directory_names_from_path().restoreDirectoryNames(it)
            }

            //then
            assertThat(restored, greaterThanOrEqualTo(3))
            assertThat(
                listDirectory("/repairSeries"), allOf(
                    hasItem<DavResource>(hasProperty("displayName", `is`("repairSeason01"))),
                    hasItem<DavResource>(hasProperty("displayName", `is`("repairSeason02")))
                )
            )
            assertThat(
                listDirectory("/repairLibrary"), hasItem<DavResource>(
                    hasProperty("displayName", `is`("repairRelease"))
                )
            )
            listDirectory("/repairSeries").filter { it.isDirectory }.forEach { assertListable(it) }
            listDirectory("/repairLibrary").filter { it.isDirectory }.forEach { assertListable(it) }

            // idempotent: nothing left to repair on a second run
            val restoredAgain = dataSource.connection.use {
                V31__restore_directory_names_from_path().restoreDirectoryNames(it)
            }
            assertThat(restoredAgain, `is`(0))
        } finally {
            runCatching { sardine.delete(url("/repairSeries")) }
            runCatching { sardine.delete(url("/repairLibrary")) }
        }
    }

    private fun corruptDirectoryName(from: String, to: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE db_item SET name = ? WHERE db_item_type = 'DbDirectory' AND name = ?"
            ).use { stmt ->
                stmt.setString(1, to)
                stmt.setString(2, from)
                assertThat("rows renamed from '$from'", stmt.executeUpdate(), `is`(1))
            }
        }
    }

    private fun assertListable(resource: DavResource) {
        try {
            sardine.list("http://localhost:${randomServerPort}${resource.href.rawPath}")
        } catch (e: SardineException) {
            fail(
                "Listed collection '${resource.displayName}' at ${resource.href} " +
                    "cannot be opened: HTTP ${e.statusCode}"
            )
        }
    }

    private fun listDirectory(path: String): List<DavResource> = sardine.list(url(path))

    private fun url(path: String) = "http://localhost:${randomServerPort}/webdav/${path.trimStart('/')}"
}
