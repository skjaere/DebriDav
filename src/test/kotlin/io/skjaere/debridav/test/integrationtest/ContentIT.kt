package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.integrationtest.config.ContentStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.TestContextInitializer.Companion.BASE_PATH
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.io.File
import java.nio.file.Files
import java.time.Instant

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@MockServerTest
class ContentIT {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var contentStubbingService: ContentStubbingService

    @AfterEach
    fun tearDown() {
        File(BASE_PATH).deleteRecursively()
    }

    @Test
    fun contentIsServed() {
        // given
        val file = File("$BASE_PATH/testfile.mp4.debridfile")
        Files.createDirectories(file.toPath().parent)
        file.createNewFile()
        val fileContents = debridFileContents.copy()
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
        file.writeText(Json.encodeToString(fileContents))

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
