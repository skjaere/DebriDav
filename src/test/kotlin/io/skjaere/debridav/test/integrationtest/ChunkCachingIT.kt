package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.cache.FileChunk
import io.skjaere.debridav.cache.FileChunkRepository
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.Blob
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.deepCopy
import io.skjaere.debridav.test.integrationtest.config.ContentStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import org.apache.commons.codec.digest.DigestUtils
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hibernate.engine.jdbc.BlobProxy
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.verify.VerificationTimes
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.*

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@MockServerTest
class ChunkCachingIT {
    @Autowired
    private lateinit var fileChunkRepository: FileChunkRepository

    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var contentStubbingService: ContentStubbingService

    @Autowired
    lateinit var debridFileContentsRepository: DebridFileContentsRepository

    @Autowired
    lateinit var mockserverClient: ClientAndServer


    @Test
    fun `that byte ranges are cached`() {
        // given
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("test")
        fileContents.size = "it works!".toByteArray().size.toLong()
        mockserverClient.reset()

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
        contentStubbingService.mockWorkingRangeStream()
        databaseFileService.createDebridFile("/testfile.mp4", hash, fileContents)
            .let { debridFileContentsRepository.save(it) }

        // when / then
        repeat(2) {
            webTestClient
                //.mutate().responseTimeout(Duration.ofMinutes(30000)).build()
                .get()
                .uri("testfile.mp4")
                .headers {
                    it.add("Range", "bytes=0-3")
                }
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectHeader().contentLength(4)
                .expectBody(String::class.java).isEqualTo("it w")
        }
        mockserverClient.verify(
            request().withMethod("GET").withPath("/workingLink"), VerificationTimes.exactly(1)
        )
    }

    @Test
    fun `that deleting remotely cached entity deleted cached chunks of that entity too`() {
        // given
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("test")
        val remotelyCachedEntity = databaseFileService.createDebridFile("/testfile.mp4", hash, fileContents)
            .let { debridFileContentsRepository.save(it) }
        val blob = Blob()
        blob.localContents = BlobProxy.generateProxy("test".toByteArray(Charsets.UTF_8))
        val chunk = FileChunk()
        chunk.remotelyCachedEntity = remotelyCachedEntity
        chunk.debridProvider = DebridProvider.PREMIUMIZE
        chunk.lastAccessed = Date.from(Instant.now())
        chunk.blob = blob
        fileChunkRepository.save(chunk)

        //when
        assertThat(fileChunkRepository.findAll().toList(), hasSize(1))
        webTestClient.delete()
            .uri("/testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        assertThat(fileChunkRepository.findAll().toList(), hasSize(0))
    }
}
