package io.skjaere.debridav.test.integrationtest

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.cache.FileChunk
import io.skjaere.debridav.cache.FileChunkCachingService
import io.skjaere.debridav.cache.FileChunkRepository
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.Blob
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.debridFileContents
import io.skjaere.debridav.test.deepCopy
import io.skjaere.debridav.test.integrationtest.config.ContentStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import jakarta.persistence.EntityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.commons.codec.digest.DigestUtils
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasSize
import org.hibernate.engine.jdbc.BlobProxy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.verify.VerificationTimes
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Duration
import java.time.Instant
import java.util.*

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debridav.debrid-clients=premiumize"
    ]
)
@MockServerTest
class ChunkCachingIT {
    @Autowired
    private lateinit var fileChunkRepository: FileChunkRepository

    @Autowired
    private lateinit var fileChunkCachingService: FileChunkCachingService


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

    @Autowired
    lateinit var premiumizeStubbingService: PremiumizeStubbingService

    @Autowired
    lateinit var entityManager: EntityManager

    @LocalServerPort
    var port: Int = 0

    @AfterEach
    fun purgeCache() {
        fileChunkCachingService.purgeCache()
        assertEquals(
            0L,
            entityManager
                .createNativeQuery(
                    "select coalesce(count(distinct loid), 0) from pg_largeobject"
                )
                .resultList
                .first()
        )
    }

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
                .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
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
    fun `that deleting remotely cached entity deletes cached chunks of that entity too`() {
        // given
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("test")
        val remotelyCachedEntity = databaseFileService.createDebridFile("/testfile.mp4", hash, fileContents)
            .let { debridFileContentsRepository.save(it) }
        val blob = Blob()
        blob.localContents = BlobProxy.generateProxy("test".toByteArray(Charsets.UTF_8))
        val chunk = FileChunk()
        chunk.remotelyCachedEntity = remotelyCachedEntity
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

    @Test
    fun `that replacing remotely cached entity deletes cached chunks of that entity too`() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "test")
        parts.part("paused", "false")

        premiumizeStubbingService.mockIsCached()
        premiumizeStubbingService.mockCachedContents()

        // when
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        val remotelyCachedEntity = databaseFileService.getFileAtPath("downloads/test/a/b/c/movie.mkv")

        val blob = Blob()
        blob.localContents = BlobProxy.generateProxy("test".toByteArray(Charsets.UTF_8))
        val chunk = FileChunk()
        chunk.remotelyCachedEntity = remotelyCachedEntity as RemotelyCachedEntity
        chunk.lastAccessed = Date.from(Instant.now())
        chunk.blob = blob
        fileChunkRepository.save(chunk)

        assertThat(fileChunkRepository.findAll().toList(), hasSize(1))
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        assertThat(fileChunkRepository.findAll().toList(), hasSize(0))

        val result = entityManager.createNativeQuery("select count(*) from pg_largeobject").resultList
        assertEquals(result.first(), 0L)

        // finally
        webTestClient.delete()
            .uri("/downloads/test")
            .exchange()
            .expectStatus().is2xxSuccessful

        assertEquals(0L, entityManager.createNativeQuery("select count(*) from blob").resultList.first())
    }

    @Test
    fun `that file chunks are cleaned up by scheduled task`() {
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

        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=0-3")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(4)
            .expectBody(String::class.java).isEqualTo("it w")

        assertEquals(1, fileChunkRepository.findAll().toList().size)
        assertEquals(1L, entityManager.createNativeQuery("select count(*) from pg_largeobject").resultList.first())
        assertEquals(1L, entityManager.createNativeQuery("select count(*) from blob").resultList.first())
        Thread.sleep(100)

        // then
        fileChunkCachingService.purgeStaleCachedChunks()
        assertEquals(0, fileChunkRepository.findAll().toList().size)
        assertEquals(0L, entityManager.createNativeQuery("select count(*) from blob").resultList.first())
        assertEquals(0L, entityManager.createNativeQuery("select count(*) from pg_largeobject").resultList.first())

        // finally
        webTestClient.delete()
            .uri("/testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful
    }

    @Test
    fun `that old entries are removed when cache is full`() {
        // given
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("test")
        fileContents.size = 1024 * 10240
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

        databaseFileService.createDebridFile("/testfile.mp4", hash, fileContents)
            .let { debridFileContentsRepository.save(it) }

        LongRange(0, 9).forEach { i ->
            val startByte = if (i == 0L) 0 else +(i * 102400)
            val endByte = startByte + 102400 - 1
            contentStubbingService.mock100kbRangeStream(startByte, endByte)
            webTestClient
                .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
                .get()
                .uri("testfile.mp4")
                .headers {
                    it.add("Range", "bytes=$startByte-$endByte")
                }
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectHeader().contentLength((102400))

        }
        assertEquals(9, fileChunkRepository.findAll().toList().size)
        assertEquals((9 * 1024 * 100).toLong(), fileChunkRepository.getTotalCacheSize())
        assertEquals(
            9L,
            entityManager.createNativeQuery("select count(distinct loid) from pg_largeobject").resultList.first()
        )
        runBlocking { delay(1000L) } //TODO: fix me!
    }

    @Test
    fun `that requests for byte ranges wait for other thread currently producing cache entry`() {
        // given
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("test")
        fileContents.size = 1024 * 1024
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

        databaseFileService.createDebridFile("/testfile.mp4", hash, fileContents)
            .let { debridFileContentsRepository.save(it) }
        contentStubbingService.mock100kbRangeStreamWithDelay(startByte = 0, endByte = 102399)

        val httpClient = HttpClient { }

        // when/then
        runBlocking {
            launch {
                val bytes = httpClient.get("http://localhost:$port/testfile.mp4") {
                    headers {
                        append("Range", "bytes=0-102399")
                    }
                }.body<ByteArray>()
                bytes
            }
            launch {
                delay(50)
                val bytes = httpClient.get("http://localhost:$port/testfile.mp4") {
                    headers {
                        append("Range", "bytes=0-102398")
                    }
                }.body<ByteArray>()
                bytes
            }

        }
    }

    @Test
    @Suppress("LongMethod")
    fun `that combining cache and http stream works`() {
        // given
        val contents = IntRange(0, 100).joinToString("\n").toByteArray(Charsets.UTF_8)
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("test")
        fileContents.size = contents.size.toLong()
        mockserverClient.reset()

        val debridLink = CachedFile(
            "testfile.mp4",
            link = "http://localhost:${contentStubbingService.port}/workingLink",
            size = contents.size.toLong(),
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
        contentStubbingService.mockWorkingRangeStream(
            0,
            10,
            contents.size.toLong(),
            contents.slice(0..10).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=0-10")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(0..10).toByteArray().toString(Charsets.UTF_8))

        contentStubbingService.mockWorkingRangeStream(
            20,
            30,
            contents.size.toLong(),
            contents.slice(20..30).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=20-30")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(20..30).toByteArray().toString(Charsets.UTF_8))

        contentStubbingService.mockWorkingRangeStream(
            40,
            50,
            contents.size.toLong(),
            contents.slice(40..50).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=40-50")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(40..50).toByteArray().toString(Charsets.UTF_8))

        contentStubbingService.mockWorkingRangeStream(
            11,
            19,
            contents.size.toLong(),
            contents.slice(11..19).toByteArray()
        )
        contentStubbingService.mockWorkingRangeStream(
            31,
            39,
            contents.size.toLong(),
            contents.slice(31..39).toByteArray()
        )
        contentStubbingService.mockWorkingRangeStream(
            51,
            contents.size.toLong() - 1,
            contents.size.toLong(),
            contents.slice(51..292).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(contents.size.toLong())
            .expectBody(String::class.java).isEqualTo(contents.toString(Charsets.UTF_8))
    }

    @Test
    @Suppress("LongMethod")
    fun `that combining http and subranges of cache trimming start works`() {
        // given
        val contents = IntRange(0, 100).joinToString("\n").toByteArray(Charsets.UTF_8)
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("test")
        fileContents.size = contents.size.toLong()
        mockserverClient.reset()

        val debridLink = CachedFile(
            "testfile.mp4",
            link = "http://localhost:${contentStubbingService.port}/workingLink",
            size = contents.size.toLong(),
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
        contentStubbingService.mockWorkingRangeStream(
            0,
            10,
            contents.size.toLong(),
            contents.slice(0..10).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=0-10")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(0..10).toByteArray().toString(Charsets.UTF_8))

        contentStubbingService.mockWorkingRangeStream(
            20,
            30,
            contents.size.toLong(),
            contents.slice(20..30).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=20-30")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(20..30).toByteArray().toString(Charsets.UTF_8))

        contentStubbingService.mockWorkingRangeStream(
            40,
            50,
            contents.size.toLong(),
            contents.slice(40..50).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=40-50")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(40..50).toByteArray().toString(Charsets.UTF_8))
        contentStubbingService.mockWorkingRangeStream(
            11,
            19,
            contents.size.toLong(),
            contents.slice(11..19).toByteArray()
        )
        contentStubbingService.mockWorkingRangeStream(
            31,
            39,
            contents.size.toLong(),
            contents.slice(31..39).toByteArray()
        )
        contentStubbingService.mockWorkingRangeStream(
            51,
            250,
            contents.size.toLong(),
            contents.slice(51..292).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=5-250")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(contents.slice(5..250).toByteArray().size.toLong())
            .expectBody(String::class.java).isEqualTo(contents.slice(5..250).toByteArray().toString(Charsets.UTF_8))

        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=5-250")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(contents.slice(5..250).toByteArray().size.toLong())
            .expectBody(String::class.java).isEqualTo(contents.slice(5..250).toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    @Suppress("LongMethod")
    fun `that combining http and subranges of cache trimming end works`() {
        // given
        val contents = IntRange(0, 100).joinToString("\n").toByteArray(Charsets.UTF_8)
        val fileContents = debridFileContents.deepCopy()
        val hash = DigestUtils.md5Hex("test")
        fileContents.size = contents.size.toLong()
        mockserverClient.reset()

        val debridLink = CachedFile(
            "testfile.mp4",
            link = "http://localhost:${contentStubbingService.port}/workingLink",
            size = contents.size.toLong(),
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
        contentStubbingService.mockWorkingRangeStream(
            0,
            10,
            contents.size.toLong(),
            contents.slice(0..10).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=0-10")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(0..10).toByteArray().toString(Charsets.UTF_8))

        contentStubbingService.mockWorkingRangeStream(
            20,
            30,
            contents.size.toLong(),
            contents.slice(20..30).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=20-30")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(20..30).toByteArray().toString(Charsets.UTF_8))

        contentStubbingService.mockWorkingRangeStream(
            40,
            50,
            contents.size.toLong(),
            contents.slice(40..50).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=40-50")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(11)
            .expectBody(String::class.java).isEqualTo(contents.slice(40..50).toByteArray().toString(Charsets.UTF_8))
        contentStubbingService.mockWorkingRangeStream(
            11,
            19,
            contents.size.toLong(),
            contents.slice(11..19).toByteArray()
        )
        contentStubbingService.mockWorkingRangeStream(
            31,
            39,
            contents.size.toLong(),
            contents.slice(31..39).toByteArray()
        )
        contentStubbingService.mockWorkingRangeStream(
            51,
            250,
            contents.size.toLong(),
            contents.slice(51..292).toByteArray()
        )
        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=5-45")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(41)
            .expectBody(String::class.java).isEqualTo(contents.slice(5..45).toByteArray().toString(Charsets.UTF_8))

        webTestClient
            .mutate().responseTimeout(Duration.ofMinutes(30000)).build()
            .get()
            .uri("testfile.mp4")
            .headers {
                it.add("Range", "bytes=5-45")
            }
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectHeader().contentLength(41)
            .expectBody(String::class.java).isEqualTo(contents.slice(5..45).toByteArray().toString(Charsets.UTF_8))
    }
}
