package io.skjaere.debridav.test.integrationtest

import io.mockk.every
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.sabnzbd.UsenetDownloadStatus
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.TorboxStubbingService
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.bramp.ffmpeg.FFprobe
import net.bramp.ffmpeg.probe.FFmpegProbeResult
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.FileSystemResource
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=torbox"]
)
@MockServerTest
class UsenetEmulationIT {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var torboxStubbingService: TorboxStubbingService

    @Autowired
    private lateinit var fileService: FileService

    @Autowired
    private lateinit var usenetRepository: UsenetRepository

    @Autowired
    private lateinit var ffprobe: FFprobe

    @AfterEach
    fun reset() {
        torboxStubbingService.reset()
        Path.of("/tmp/releaseName.nzb").toFile().let {
            if (it.exists()) it.delete()
        }
    }

    @BeforeEach
    fun setup() {
        Path.of("/tmp/releaseName.nzb").toFile().let {
            if (it.exists()) it.delete()
        }
    }

    @Test
    fun thatInvalidFileGetsDeletedAndDownloadMarkedAsFailed() {
        // given
        torboxStubbingService.stubAddNzbResponse()
        torboxStubbingService.stubUsenetListResponse()
        torboxStubbingService.stubUsenetDownloadLink()

        val nzbFile = Files.createFile(Path.of("/tmp/releaseName.nzb")).toFile()

        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("name", FileSystemResource(nzbFile))
        parts.part("cat", "tv")

        every { ffprobe.probe(any()) } answers {
            assertNotNull(fileService.getFileAtPath("/downloads/releaseName/video.mkv"))
            throw IOException("Error")
        }

        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        val usenetDownload = usenetRepository.findAll().firstOrNull { it.debridId == 0L }
        assertNotNull(usenetDownload)
        await()/*.atMost(Duration.ofDays(1))*/.until {
            usenetRepository.findAll()
                .firstOrNull { it.debridId == 0L }
                ?.status?.let {
                    it == UsenetDownloadStatus.FAILED
                } ?: false
        }
        assertNull(fileService.getFileAtPath("/downloads/releaseName/video.mkv"))

        usenetRepository.deleteAll()
    }

    @Test
    fun thatValidFileGetsStoredAndDownloadMarkedAsCompleted() {
        // given
        torboxStubbingService.stubAddNzbResponse()
        torboxStubbingService.stubUsenetListResponse()
        torboxStubbingService.stubUsenetDownloadLink()

        val nzbFile = Files.createFile(Path.of("/tmp/releaseName.nzb")).toFile()

        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("name", FileSystemResource(nzbFile))
        parts.part("cat", "tv")

        every { ffprobe.probe(any()) } returns FFmpegProbeResult()

        // when
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        val usenetDownload = usenetRepository.findAll().firstOrNull { it.debridId == 0L }
        assertNotNull(usenetDownload)
        await()/*.atMost(Duration.ofDays(1))*/.until {
            usenetRepository.findAll()
                .firstOrNull { it.debridId == 0L }
                ?.status?.let {
                    it == UsenetDownloadStatus.COMPLETED
                } ?: false
        }
        assertNotNull(fileService.getFileAtPath("/downloads/releaseName/video.mkv"))
        assertEquals("releaseName", usenetRepository.findById(usenetDownload.id!!).get().storagePath)

        // finally
        fileService.deleteFile("/downloads/releaseName/video.mkv")
        usenetRepository.deleteAll()
        nzbFile.delete()
    }

    @Test
    fun thatDownloadMissingAtProviderGetsMarkedAsFailed() {
        // given
        torboxStubbingService.stubAddNzbResponse()
        torboxStubbingService.stubMissingListResponse()

        val nzbFile = Files.createFile(Path.of("/tmp/releaseName.nzb")).toFile()

        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("name", FileSystemResource(nzbFile))
        parts.part("cat", "tv")

        every { ffprobe.probe(any()) } returns FFmpegProbeResult()

        // when
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        val usenetDownload = usenetRepository.findAll().firstOrNull { it.debridId == 0L }
        assertNotNull(usenetDownload)
        await()/*.atMost(Duration.ofDays(1))*/.until {
            usenetRepository.findAll()
                .firstOrNull { it.debridId == 0L }
                ?.status?.let {
                    it == UsenetDownloadStatus.FAILED
                } ?: false
        }
        assertNull(fileService.getFileAtPath("/downloads/releaseName/video.mkv"))

        // finally
        usenetRepository.deleteAll()
    }

    @Test
    fun thatRootDirectoryOfDownloadIsReleaseName() {
        // given
        torboxStubbingService.stubAddNzbResponse()
        torboxStubbingService.stubUsenetListResponse()
        torboxStubbingService.stubUsenetDownloadLink()

        val nzbFile = Files.createFile(Path.of("/tmp/releaseName.nzb")).toFile()

        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("name", FileSystemResource(nzbFile))
        parts.part("cat", "tv")

        every { ffprobe.probe(any()) } returns FFmpegProbeResult()

        // when
        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        val usenetDownload = usenetRepository.findAll().firstOrNull { it.debridId == 0L }
        assertNotNull(usenetDownload)
        await()/*.atMost(Duration.ofDays(1))*/.until {
            usenetRepository.findAll()
                .firstOrNull { it.debridId == 0L }
                ?.status?.let {
                    it == UsenetDownloadStatus.COMPLETED
                } ?: false
        }
        assertNotNull(fileService.getFileAtPath("/downloads/releaseName/video.mkv"))
        assertEquals("releaseName", usenetRepository.findById(usenetDownload.id!!).get().storagePath)

        val historyParts = MultipartBodyBuilder()
        historyParts.part("mode", "history")

        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots[0].storage")
            .isEqualTo("/data/downloads/releaseName")

        // finally
        fileService.deleteFile("/downloads/releaseName/video.mkv")
        usenetRepository.deleteAll()
    }
}
