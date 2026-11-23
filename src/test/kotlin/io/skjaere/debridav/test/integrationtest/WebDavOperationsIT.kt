package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import com.github.sardine.impl.SardineException
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import kotlin.test.assertFailsWith
import kotlin.test.fail
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class WebDavOperationsIT {
    private val logger = LoggerFactory.getLogger(WebDavOperationsIT::class.java)

    @Autowired
    lateinit var debridFileContentsRepository: DebridFileContentsRepository

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    private var baselineEntityCount: Int = -1

    @BeforeEach
    fun captureBaseline() {
        baselineEntityCount = debridFileContentsRepository.findAll().toList().size
    }

    @Test
    fun thatCreatingFileInRootWorks() {
        //when
        sardine.put("http://localhost:${randomServerPort}/webdav/testfile.txt", "test contents".byteInputStream())

        //then
        val listOfFiles: List<DavResource> = listDirectory("/")
        assertThat(
            listOfFiles, hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testfile.txt")
                )
            )
        )

        //finally
        deleteFile("testfile.txt")
        assertReset()
    }

    @Test
    fun thatDeletingFileInRootWorks() {
        //given
        sardine.put("http://localhost:${randomServerPort}/webdav/testfile.txt", "test contents".byteInputStream())
        val listOfFiles: List<DavResource> = listDirectory("/")
        assertThat(
            listOfFiles, hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testfile.txt")
                )
            )
        )

        //when
        deleteFile("testfile.txt")

        //then
        assertThat(
            listDirectory("/"), not(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("testfile.txt")
                    )
                )
            )
        )
        assertReset()
    }

    @Test
    fun thatCreatingDirectoryInRootWorks() {
        //when
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        val listOfFiles: List<DavResource> = listDirectory("/")

        //then
        assertThat(
            listOfFiles, hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testDirectory")
                )
            )
        )

        //finally
        deleteFile("testDirectory/")
        assertReset()
    }

    @Test
    fun thatRenamingEmptyDirectoryInRootWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        assertThat(
            listDirectory("/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testDirectory")
                )
            )
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/webdav/testDirectory",
            "http://localhost:${randomServerPort}/webdav/movedTestDirectory"
        )

        //then
        assertThat(
            listDirectory("/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("movedTestDirectory")
                )
            )
        )

        //finally
        deleteFile("movedTestDirectory")
        assertReset()
    }

    @Test
    fun thatRenamingPopulatedDirectoryInRootWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        sardine.put(
            "http://localhost:${randomServerPort}/webdav/testDirectory/testfile.txt",
            "test contents".byteInputStream()
        )
        assertThat(
            listDirectory("/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testDirectory")
                )
            )
        )
        assertThat(
            listDirectory("/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testfile.txt")
                )
            )
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/webdav/testDirectory",
            "http://localhost:${randomServerPort}/webdav/movedTestDirectory"
        )

        //then
        assertThat(
            listDirectory("/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("movedTestDirectory")
                )
            )
        )
        assertThat(
            listDirectory("/movedTestDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testfile.txt")
                )
            )
        )

        //finally
        deleteFile("movedTestDirectory")
        assertReset()
    }

    @Test
    fun thatRenamingEmptyDirectoryInBranchWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory/nestedDirectory")
        assertThat(
            listDirectory("/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testDirectory")
                )
            )
        )
        assertThat(
            listDirectory("/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("nestedDirectory")
                )
            )
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/webdav/testDirectory/nestedDirectory",
            "http://localhost:${randomServerPort}/webdav/testDirectory/renamedNestedDirectory"
        )

        //then
        assertThat(
            listDirectory("/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("renamedNestedDirectory")
                )
            )
        )

        //finally
        deleteFile("testDirectory")
        assertReset()
    }

    @Test
    fun thatRenamingPopulatedDirectoryInBranchWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory/")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory/nestedDirectory")
        sardine.put(
            "http://localhost:${randomServerPort}/webdav/testDirectory/nestedDirectory/testfile.txt",
            "test contents".byteInputStream()
        )
        assertThat(
            listDirectory("/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testDirectory")
                )
            )
        )
        assertThat(
            listDirectory("/testDirectory/nestedDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testfile.txt")
                )
            )
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/webdav/testDirectory/nestedDirectory",
            "http://localhost:${randomServerPort}/webdav/testDirectory/renamedNestedDirectory"
        )

        //then
        assertThat(
            listDirectory("/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("renamedNestedDirectory")
                )
            )
        )

        //finally
        deleteFile("testDirectory")
        assertReset()
    }

    @Test
    fun thatMovingDirectoryToNestedDirectoryWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory/nestedTestDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/directoryToBeMoved")
        assertThat(
            listDirectory("/"), allOf(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("testDirectory")
                    )
                ),
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("directoryToBeMoved")
                    )
                )
            )
        )
        assertThat(
            listDirectory("/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("nestedTestDirectory")
                )
            )
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/webdav/directoryToBeMoved",
            "http://localhost:${randomServerPort}/webdav/testDirectory/nestedTestDirectory/directoryToBeMoved"
        )

        //then
        assertThat(
            listDirectory("testDirectory/nestedTestDirectory/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("directoryToBeMoved")
                )
            )
        )

        assertThat(
            listDirectory("/"), not(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("movedTestDirectory")
                    )
                )
            )
        )

        //finally
        deleteFile("testDirectory")
        assertReset()
    }

    @Test
    fun thatMovingDirectoryWithFilesWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/destinationDirectory")
        sardine.put(
            "http://localhost:${randomServerPort}/webdav/testDirectory/testfile.txt",
            "test contents".byteInputStream()
        )
        assertThat(
            listDirectory("/"), allOf(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("testDirectory")
                    )
                ),
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("destinationDirectory")
                    )
                )
            )
        )
        assertThat(
            listDirectory("/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testfile.txt")
                )
            )
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/webdav/testDirectory",
            "http://localhost:${randomServerPort}/webdav/destinationDirectory/testDirectory"
        )

        //then
        assertThat(
            listDirectory("destinationDirectory/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testfile.txt")
                )
            )
        )

        assertThat(
            listDirectory("/"), not(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("testDirectory")
                    )
                )
            )
        )

        //finally
        deleteFile("destinationDirectory")
        assertReset()
    }

    @Test
    fun thatMovingDirectoryWithSubdirectoriesWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory/subDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/destinationDirectory")

        assertThat(
            listDirectory("/"), allOf(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("testDirectory")
                    )
                ),
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("destinationDirectory")
                    )
                )
            )
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/webdav/testDirectory",
            "http://localhost:${randomServerPort}/webdav/destinationDirectory/testDirectory"
        )

        //then
        assertThat(
            listDirectory("destinationDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testDirectory")
                )
            )
        )
        assertThat(
            listDirectory("destinationDirectory/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("subDirectory")
                )
            )
        )

        assertThat(
            listDirectory("/"), not(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("testDirectory")
                    )
                )
            )
        )

        //finally
        deleteFile("destinationDirectory")
        assertReset()
    }

    /**
     * Renaming a directory in place (e.g. Sonarr renaming a series folder that holds season
     * folders) must only rename that directory, not every subdirectory beneath it.
     */
    @Test
    fun thatRenamingDirectoryWithSubdirectoriesKeepsSubdirectoryNames() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/seriesDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/seriesDirectory/season01")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/seriesDirectory/season01/extras")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/seriesDirectory/season02")
        sardine.put(
            "http://localhost:${randomServerPort}/webdav/seriesDirectory/season01/episode01.txt",
            "test contents".byteInputStream()
        )

        try {
            //when
            sardine.move(
                "http://localhost:${randomServerPort}/webdav/seriesDirectory",
                "http://localhost:${randomServerPort}/webdav/renamedSeriesDirectory"
            )

            //then
            assertThat(
                listDirectory("/renamedSeriesDirectory"), allOf(
                    hasItem<DavResource>(hasProperty("displayName", `is`("season01"))),
                    hasItem<DavResource>(hasProperty("displayName", `is`("season02")))
                )
            )
            assertThat(
                listDirectory("/renamedSeriesDirectory/season01"), allOf(
                    hasItem<DavResource>(hasProperty("displayName", `is`("extras"))),
                    hasItem<DavResource>(hasProperty("displayName", `is`("episode01.txt")))
                )
            )
            // rclone navigates by the hrefs in each listing, so every listed collection must open
            listDirectory("/renamedSeriesDirectory").filter { it.isDirectory }.forEach { assertListable(it) }
            listDirectory("/renamedSeriesDirectory/season01").filter { it.isDirectory }.forEach { assertListable(it) }
        } finally {
            deleteIfExists("renamedSeriesDirectory")
            deleteIfExists("seriesDirectory")
        }
        assertReset()
    }

    /**
     * A single MOVE that changes both the parent and the name (e.g. `mv /downloads/A /tv/B`)
     * must leave the directory reachable at its new name.
     */
    @Test
    fun thatMovingDirectoryToAnotherParentWithNewNameWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/downloadsDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/downloadsDirectory/releaseDirectory")
        sardine.createDirectory(
            "http://localhost:${randomServerPort}/webdav/downloadsDirectory/releaseDirectory/subDirectory"
        )
        sardine.put(
            "http://localhost:${randomServerPort}/webdav/downloadsDirectory/releaseDirectory/testfile.txt",
            "test contents".byteInputStream()
        )
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/libraryDirectory")

        try {
            //when
            sardine.move(
                "http://localhost:${randomServerPort}/webdav/downloadsDirectory/releaseDirectory",
                "http://localhost:${randomServerPort}/webdav/libraryDirectory/renamedReleaseDirectory"
            )

            //then
            assertThat(
                listDirectory("/libraryDirectory"), hasItem<DavResource>(
                    hasProperty("displayName", `is`("renamedReleaseDirectory"))
                )
            )
            assertThat(
                listDirectoryOrFail("/libraryDirectory/renamedReleaseDirectory"), allOf(
                    hasItem<DavResource>(hasProperty("displayName", `is`("subDirectory"))),
                    hasItem<DavResource>(hasProperty("displayName", `is`("testfile.txt")))
                )
            )
            listDirectory("/libraryDirectory").filter { it.isDirectory }.forEach { assertListable(it) }
            assertThat(
                listDirectory("/downloadsDirectory"), not(
                    hasItem<DavResource>(hasProperty("displayName", `is`("releaseDirectory")))
                )
            )
        } finally {
            deleteIfExists("libraryDirectory")
            deleteIfExists("downloadsDirectory")
        }
        assertReset()
    }

    @Test
    fun thatMovingLocalEntityWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        sardine.put(
            "http://localhost:${randomServerPort}/webdav/testfile.txt",
            "test contents".byteInputStream()
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/webdav/testfile.txt",
            "http://localhost:${randomServerPort}/webdav/testDirectory/testfile.txt",
        )

        // then
        assertThat(
            listDirectory("/testDirectory"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testfile.txt")
                )
            )
        )
        deleteFile("testDirectory")
        assertReset()
    }

    @Test
    fun thatDeletingDirectoryBranchWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/webdav/testDirectory/subDirectory")
        sardine.createDirectory(
            "http://localhost:${randomServerPort}/webdav/testDirectory/subDirectory/secondSubDirectory"
        )

        assertThat(
            listDirectory("/"), allOf(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("testDirectory")
                    )
                )
            )
        )
        assertThat(
            listDirectory("/testDirectory"), allOf(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("subDirectory")
                    )
                )
            )
        )
        assertThat(
            listDirectory("/testDirectory/subDirectory"), allOf(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("secondSubDirectory")
                    )
                )
            )
        )

        //when
        sardine.delete("http://localhost:${randomServerPort}/webdav/testDirectory")

        //then
        assertThat(
            listDirectory("/"), not(
                hasItem<DavResource>(
                    hasProperty(
                        "displayName", `is`("testDirectory")
                    )
                )
            )
        )
        assertReset()
    }

    @Test
    fun thatReadingLocalEntityWorks() {
        // given
        sardine.put(
            "http://localhost:${randomServerPort}/webdav/testDirectory/testfile.txt",
            "test contents".byteInputStream()
        )

        // when
        val response = sardine.get(
            "http://localhost:${randomServerPort}/webdav/testDirectory/testfile.txt"
        ).readAllBytes().decodeToString()

        assertThat(response, `is`("test contents"))
        deleteFile("testDirectory")
        assertReset()
    }

    @Test
    fun thatReadingLocalEntityWithRangeWorks() {
        // given
        sardine.put(
            "http://localhost:${randomServerPort}/webdav/testDirectory/testfile.txt",
            "test contents".byteInputStream()
        )

        // when
        val response = sardine.get(
            "http://localhost:${randomServerPort}/webdav/testDirectory/testfile.txt",
            mapOf(
                "Range" to "bytes=0-0",
            )
        ).readAllBytes().decodeToString()

        assertThat(response, `is`("t"))
        deleteFile("testDirectory")
        assertReset()
    }

    @Test
    fun thatCreatingLocalEntityLargerThanSetMaximumFails() {
        //when
        val contents = IntRange(0, (1024 * 1024 * 2)).map { Byte.MIN_VALUE }.toByteArray()
        assertFailsWith<SardineException> {
            sardine.put(
                "http://localhost:${randomServerPort}/webdav/testfile.txt",
                contents.inputStream()
            )
        }
    }

    private fun assertReset() {
        debridFileContentsRepository.findAll()
            .toList().let {
                if (it.size != baselineEntityCount) {
                    it.forEach { logger.error("item found ${it.name}") }
                }
                assertThat(it, hasSize(baselineEntityCount))
            }
    }

    private fun listDirectory(path: String): List<DavResource> =
        sardine.list("http://localhost:${randomServerPort}/webdav/${path.trimStart('/')}")

    private fun deleteFile(path: String) =
        sardine.delete("http://localhost:${randomServerPort}/webdav/${path.trimStart('/')}")

    private fun deleteIfExists(path: String) {
        runCatching { deleteFile(path) }
    }

    private fun listDirectoryOrFail(path: String): List<DavResource> = try {
        listDirectory(path)
    } catch (e: SardineException) {
        fail("PROPFIND /webdav/${path.trimStart('/')} failed with HTTP ${e.statusCode}")
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
}
