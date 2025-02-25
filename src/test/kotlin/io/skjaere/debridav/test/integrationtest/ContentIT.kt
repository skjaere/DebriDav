package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.deepCopy
import io.skjaere.debridav.test.integrationtest.config.ContentStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.TestContextInitializer.Companion.BASE_PATH
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.io.File
import java.time.Instant

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@MockServerTest
class ContentIT {
    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var contentStubbingService: ContentStubbingService

    @Autowired
    lateinit var debridFileContentsRepository: DebridFileContentsRepository

    @AfterEach
    fun tearDown() {
        File(BASE_PATH).deleteRecursively()
    }

    @Test
    fun contentIsServed() {
        // given
        val fileContents = debridFileContents.deepCopy()
        fileContents.size = "it works!".toByteArray().size.toLong()

        val debridLink = CachedFile(
            "testfile.mp4",
            link = "http://localhost:${contentStubbingService.port}/workingLink",
            size = "it works!".toByteArray().size.toLong(),
            provider = DebridProvider.PREMIUMIZE,
            lastChecked = Instant.now().toEpochMilli(),
            params = mapOf(),
            mimeType = "video/mp4"
        )
        fileContents.debridLinks = mutableListOf(debridLink)
        contentStubbingService.mockWorkingStream()
        databaseFileService.createDebridFile("/testfile.mp4", fileContents)
            .let { debridFileContentsRepository.save(it) }

        // when / then
        webTestClient
            .get()
            .uri("testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .isEqualTo("it works!")

        webTestClient.delete()
            .uri("/testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful
    }
}
