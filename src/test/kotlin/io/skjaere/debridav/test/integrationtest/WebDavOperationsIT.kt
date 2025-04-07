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
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
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

    @Test
    fun thatCreatingFileInRootWorks() {
        //when
        sardine.put("http://localhost:${randomServerPort}/testfile.txt", "test contents".byteInputStream())

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
        sardine.put("http://localhost:${randomServerPort}/testfile.txt", "test contents".byteInputStream())
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
        assertThat(
            listDirectory("/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("testDirectory")
                )
            )
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/testDirectory",
            "http://localhost:${randomServerPort}/movedTestDirectory"
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
        sardine.put(
            "http://localhost:${randomServerPort}/testDirectory/testfile.txt",
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
            "http://localhost:${randomServerPort}/testDirectory",
            "http://localhost:${randomServerPort}/movedTestDirectory"
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory/nestedDirectory")
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
            "http://localhost:${randomServerPort}/testDirectory/nestedDirectory",
            "http://localhost:${randomServerPort}/testDirectory/renamedNestedDirectory"
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory/")
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory/nestedDirectory")
        sardine.put(
            "http://localhost:${randomServerPort}/testDirectory/nestedDirectory/testfile.txt",
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
            "http://localhost:${randomServerPort}/testDirectory/nestedDirectory",
            "http://localhost:${randomServerPort}/testDirectory/renamedNestedDirectory"
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory/nestedTestDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/directoryToBeMoved")
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
            "http://localhost:${randomServerPort}/directoryToBeMoved",
            "http://localhost:${randomServerPort}/testDirectory/nestedTestDirectory/directoryToBeMoved"
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/destinationDirectory")
        sardine.put(
            "http://localhost:${randomServerPort}/testDirectory/testfile.txt",
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
            "http://localhost:${randomServerPort}/testDirectory",
            "http://localhost:${randomServerPort}/destinationDirectory/testDirectory"
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory/subDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/destinationDirectory")

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
            "http://localhost:${randomServerPort}/testDirectory",
            "http://localhost:${randomServerPort}/destinationDirectory/testDirectory"
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

    @Test
    fun thatMovingLocalEntityWorks() {
        //given
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
        sardine.put(
            "http://localhost:${randomServerPort}/testfile.txt",
            "test contents".byteInputStream()
        )

        //when
        sardine.move(
            "http://localhost:${randomServerPort}/testfile.txt",
            "http://localhost:${randomServerPort}/testDirectory/testfile.txt",
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
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory/subDirectory")
        sardine.createDirectory("http://localhost:${randomServerPort}/testDirectory/subDirectory/secondSubDirectory")

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
        sardine.delete("http://localhost:${randomServerPort}/testDirectory")

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
            "http://localhost:${randomServerPort}/testDirectory/testfile.txt",
            "test contents".byteInputStream()
        )

        // when
        val response = sardine.get(
            "http://localhost:${randomServerPort}/testDirectory/testfile.txt"
        ).readAllBytes().decodeToString()

        assertThat(response, `is`("test contents"))
        deleteFile("testDirectory")
        assertReset()
    }

    @Test
    fun thatReadingLocalEntityWithRangeWorks() {
        // given
        sardine.put(
            "http://localhost:${randomServerPort}/testDirectory/testfile.txt",
            "test contents".byteInputStream()
        )

        // when
        val response = sardine.get(
            "http://localhost:${randomServerPort}/testDirectory/testfile.txt",
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
                "http://localhost:${randomServerPort}/testfile.txt",
                contents.inputStream()
            )
        }
    }

    private fun assertReset() {
        debridFileContentsRepository.findAll()
            .toList().let {
                if (it.size != 4) {
                    it.forEach { logger.error("item found ${it.name}") }
                }
                assertThat(it, hasSize(4))
            }

    }

    private fun listDirectory(path: String): List<DavResource> =
        sardine.list("http://localhost:${randomServerPort}/$path")

    private fun deleteFile(path: String) =
        sardine.delete("http://localhost:${randomServerPort}/$path")
}
