package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.mocknntp.testcontainer.client.NzbFileSpec
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerNntpTest
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import io.skjaere.debridav.test.integrationtest.config.awaitSabImportCompletion
import io.skjaere.debridav.test.integrationtest.config.awaitSabImportFailure
import io.skjaere.debridav.usenet.nzb.NzbArchiveType
import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class, TestSecurityConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=easynews"]
)
@MockServerNntpTest
class NzbImportIT {

    @Autowired
    private lateinit var usenetRepository: UsenetRepository

    @Autowired
    private lateinit var nzbDocumentRepository: NzbDocumentRepository

    @Autowired
    private lateinit var nzbImportRepository: io.skjaere.debridav.repository.NzbImportRepository

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var mockNntpServerContainer: MockNntpServerContainer

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    private val createdReleases = mutableListOf<String>()

    @AfterEach
    fun tearDown() {
        runBlocking { mockNntpServerContainer.client.clearYencBodyExpectations() }
        for (releaseName in createdReleases) {
            @Suppress("TooGenericExceptionCaught")
            try {
                sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/$releaseName")
            } catch (_: Exception) {
                // directory may not exist if import failed
            }
        }
        createdReleases.clear()
        usenetRepository.deleteAll()
        nzbDocumentRepository.deleteAll()
    }

    @Test
    fun `NZB import with RAR4 single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.RAR4, "rar4-test-release", NzbArchiveType.RAR)
    }

    @Test
    fun `NZB import with RAR5 single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.RAR5, "rar5-test-release", NzbArchiveType.RAR)
    }

    @Test
    fun `NZB import with 7zip single-volume archive produces completed download with files on WebDAV`() {
        nzbImportTest(ContainerType.SEVENZIP, "7zip-test-release", NzbArchiveType.SEVEN_ZIP)
    }

    @Test
    fun `NZB import with non-archive data produces completed download with raw metadata`() {
        val releaseName = "raw-data-test"
        createdReleases.add(releaseName)
        // Garbage data that is not a valid archive format — treated as Raw metadata
        val garbageData = ByteArray(32 * 1024) { 0x42 }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareNzb(
                files = listOf(NzbFileSpec("data.bin", garbageData))
            )
        }

        uploadNzb(nzbXml, releaseName)
        waitForCompletion(releaseName)

        // verify history shows COMPLETED status
        val historyParts = MultipartBodyBuilder()
        historyParts.part("mode", "history")
        historyParts.part("cat", "testcat")
        webTestClient.post().uri("/api?apikey=test-apikey")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots[?(@.name=='$releaseName')].status")
            .isEqualTo("COMPLETED")

        // verify archive type is RAW
        val nzbDocuments = nzbDocumentRepository.findAll().toList()
        assertThat("Should have exactly one NZB document", nzbDocuments.size, `is`(1))
        assertThat("Archive type should be RAW", nzbDocuments[0].archiveType, `is`(NzbArchiveType.RAW))
    }

    /**
     * Regression guard for the prod incident where 21 House.S06 NZBs ended up with
     * usenet_download.status=COMPLETED but zero files. SABnzbd's history endpoint
     * then told Sonarr the release was at /data/downloads/<name>, Sonarr looked
     * via WebDAV and found nothing, and reported "No files eligible for import".
     *
     * The chain that produced that state required prepare() to surface a Success
     * result while the underlying articles were unreachable on the NNTP server.
     * This test pins the contract from the other direction: when the server
     * itself reports missing articles, the import MUST end up FAILED — never
     * COMPLETED — and no NZB document or WebDAV directory should be left behind.
     */
    @Test
    fun `NZB import is marked FAILED when NNTP server reports missing articles`() {
        val releaseName = "missing-articles-test-release"
        createdReleases.add(releaseName)
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = ContainerType.RAR5
            )
        }
        // Drop every yenc body expectation so the mock returns "430 No Such
        // Article Found" for any BODY fetch the importer issues — same wire
        // signal we'd see from a real provider after retention drop / DMCA.
        runBlocking { mockNntpServerContainer.client.clearYencBodyExpectations() }

        uploadNzb(nzbXml, releaseName)

        waitForFailure(releaseName)

        assertThat(
            "No NZB document should be persisted for a missing-article import",
            nzbDocumentRepository.findAll().toList(),
            `is`(empty())
        )
        assertThat(
            "No WebDAV directory should exist for the failed release",
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/")
                .map { it.displayName },
            not(hasItem(releaseName))
        )
    }

    @Test
    @Disabled
    fun `adding the same NZB twice overwrites DB entries and files remain accessible`() {
        val releaseName = "duplicate-nzb-test"
        createdReleases.add(releaseName)
        val containerType = ContainerType.RAR5
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = containerType
            )
        }

        // first upload
        uploadNzb(nzbXml, releaseName)
        waitForCompletion(releaseName)

        val entriesAfterFirst = usenetRepository.findAll().filter { it.name == releaseName }
        assertThat("Should have exactly one DB entry after first import", entriesAfterFirst.size, `is`(1))

        // clear NNTP expectations and re-prepare so the second upload can also be served
        runBlocking { mockNntpServerContainer.client.clearYencBodyExpectations() }
        val nzbXml2 = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf("testfile.bin" to testData),
                containerType = containerType
            )
        }

        // second upload of the same release
        uploadNzb(nzbXml2, releaseName)
        waitForCompletion(releaseName)

        // verify only one DB entry exists (overwritten, not duplicated)
        val entriesAfterSecond = usenetRepository.findAll().filter { it.name == releaseName }
        assertThat("Should still have exactly one DB entry after second import", entriesAfterSecond.size, `is`(1))

        // verify WebDAV still has the release directory with the file (display name is
        // the release name + .bin because the archive contained one file)
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$releaseName"),
            hasItem<DavResource>(hasProperty("displayName", `is`("$releaseName.bin")))
        )
    }

    @Test
    fun `fully-obscured single-file NZB does not produce a double or mixed extension`() {
        // When BOTH outer (upload filename) and inner (per-file in NZB) are obscured
        // we can't promote, but we also must not append .ext to a name that already
        // ends in a container ext — produced 'release.mkv.mkv' (duplicate) or
        // 'release.mkv.mp4' (mixed, the harder case where outer + inner ext differ)
        // on disk before this guard. Use mixed-ext (.mkv outer, .mp4 inner) — that's
        // the case the user actually hit and the same-ext case is the easier subset.
        val obscuredOuter = "29172H3N9n3n0n42n10w12h76K563201.mkv"
        val obscuredInner = "ENjaPnnFWf9V6FlM1Id2hYpljVnAYinN.mp4"
        val expectedStem = "29172H3N9n3n0n42n10w12h76K563201"
        createdReleases.add(obscuredOuter)
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf(obscuredInner to testData),
                containerType = ContainerType.RAR5
            )
        }

        uploadNzb(nzbXml, obscuredOuter)
        waitForCompletion(obscuredOuter)

        val displayed = sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$obscuredOuter")
            .map { it.displayName }
        // Trailing container ext on the release name gets stripped before appending
        // the inner's ext — single clean trailing extension is what we want.
        assertThat(displayed, hasItem("$expectedStem.mp4"))
        assertThat(displayed.filter { it.endsWith(".mkv.mp4") || it.endsWith(".mp4.mkv") }, `is`(emptyList()))
    }

    @Test
    fun `obscured upload name is replaced by clean inner filename when the NZB contains a single file`() {
        // Real-world failure mode: Sportarr/Prowlarr uploaded the NZB under a hash-based
        // filename (no <head><meta type="title">, no nzbname), but the inner per-file
        // name was clean. Without promotion both the directory and the surfaced file
        // inherited the hash.
        val obscuredUploadName = "3709r79e19m1m1m18m23a15h85z04853.mkv"  // upload filename
        val cleanInnerName = "Release.Name.S01E05.1080p.WEB-DL.x264-PLACEHOLDER.mkv"
        createdReleases.add(obscuredUploadName)
        // base names are added too — directory listing changes after promotion
        val promoted = "Release.Name.S01E05.1080p.WEB-DL.x264-PLACEHOLDER"
        createdReleases.add(promoted)

        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf(cleanInnerName to testData),
                containerType = ContainerType.RAR5
            )
        }

        uploadNzb(nzbXml, obscuredUploadName)
        waitForCompletion(promoted)

        // The promoted name (NOT the obscured upload name) is the release directory.
        val rootListing = sardine.list("http://localhost:${randomServerPort}/webdav/downloads/")
            .map { it.displayName }
        assertThat(rootListing, hasItem(promoted))
        assertThat(rootListing, not(hasItem(obscuredUploadName)))

        // And the file inside is named after the promoted release name + extension.
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$promoted"),
            hasItem<DavResource>(hasProperty("displayName", `is`("$promoted.mkv")))
        )

        // The import-history view reads nzb_import.name — that needs to track the
        // promotion too, otherwise the SABnzbd /history endpoint and debridav's own
        // /api/v1/queue UI continue to show the obfuscated upload name.
        val importNames = nzbImportRepository.findAll().map { it.name }
        assertThat(importNames, hasItem(promoted))
        assertThat(importNames, not(hasItem(obscuredUploadName)))
    }

    @Test
    fun `single-file NZB surfaces the release name as the WebDAV filename`() {
        // Usenet obfuscation: the file inside the archive carries a yenc-mangled name
        // ('oYBVxs...'). debridav should surface the canonical release name + extension
        // so *arr file-naming and casual browsing both see something parseable.
        val releaseName = "UFC.310.Jones.vs.Miocic.2026.WEB-DL.x264-MMA"
        val obscuredFileName = "oYBVxskQWIaXbaZLlbhl55MS78I05WgC.mp4"
        createdReleases.add(releaseName)
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf(obscuredFileName to testData),
                containerType = ContainerType.RAR5
            )
        }

        uploadNzb(nzbXml, releaseName)
        waitForCompletion(releaseName)

        // Display path keeps the original extension and uses the release name.
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$releaseName"),
            hasItem<DavResource>(hasProperty("displayName", `is`("$releaseName.mp4")))
        )
        // The obscured name is NOT exposed on the WebDAV mount.
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$releaseName")
                .map { it.displayName },
            not(hasItem(obscuredFileName))
        )
    }

    @Test
    fun `multi-file NZB keeps each file's original name (release name only identifies the bundle)`() {
        val releaseName = "TV.Pack.Season.1.2026"
        createdReleases.add(releaseName)
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        // Two files in the archive — the release name applies to the *bundle*, so we
        // shouldn't try to collapse both into one filename.
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf(
                    "TV.Pack.S01E01.bin" to testData,
                    "TV.Pack.S01E02.bin" to testData,
                ),
                containerType = ContainerType.RAR5
            )
        }

        uploadNzb(nzbXml, releaseName)
        waitForCompletion(releaseName)

        val displayNames = sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$releaseName")
            .map { it.displayName }
        assertThat(displayNames, hasItem("TV.Pack.S01E01.bin"))
        assertThat(displayNames, hasItem("TV.Pack.S01E02.bin"))
    }

    private fun nzbImportTest(containerType: ContainerType, releaseName: String, expectedArchiveType: NzbArchiveType) {
        createdReleases.add(releaseName)
        // given - test data must be >16KB for nzb-streamer enrichment.
        // Use an obscured filename (long alphanumeric, no separators) so the
        // displayFileNameFor() rename heuristic triggers and the file is surfaced
        // as `<releaseName>.bin` on the mount — which is the behavior this test asserts.
        val testData = ByteArray(32 * 1024) { (it % 256).toByte() }
        val obscuredFilename = "aBCdEf1234567890abCdEf1234567890.bin"
        val nzbXml = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(
                fileContents = mapOf(obscuredFilename to testData),
                containerType = containerType
            )
        }

        // when - upload NZB via SABnzbd API
        uploadNzb(nzbXml, releaseName)

        // then - poll history until COMPLETED or timeout
        waitForCompletion(releaseName)

        // verify WebDAV has the release directory
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/"),
            hasItem<DavResource>(hasProperty("displayName", `is`(releaseName)))
        )

        // verify WebDAV has the extracted file inside the release directory
        // Display name uses the release name + original extension because the archive
        // contained exactly one file — see displayFileNameFor() in NzbImportService.
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/$releaseName"),
            hasItem<DavResource>(hasProperty("displayName", `is`("$releaseName.bin")))
        )

        // verify history shows correct storage path
        val historyParts = MultipartBodyBuilder()
        historyParts.part("mode", "history")
        historyParts.part("cat", "testcat")
        webTestClient.post().uri("/api?apikey=test-apikey")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots[?(@.name=='$releaseName')].storage")
            .isEqualTo("/data/downloads/$releaseName")

        // verify the persisted archive type
        val nzbDocuments = nzbDocumentRepository.findAll().toList()
        assertThat("Should have exactly one NZB document", nzbDocuments.size, `is`(1))
        assertThat("Archive type should match", nzbDocuments[0].archiveType, `is`(expectedArchiveType))
    }

    private fun uploadNzb(nzbXml: String, releaseName: String) {
        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("cat", "testcat")
        parts.part("name", nzbXml.toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=$releaseName.nzb")

        webTestClient.post().uri("/api?apikey=test-apikey").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(parts.build())).exchange().expectStatus().is2xxSuccessful
    }

    private fun waitForFailure(releaseName: String) =
        webTestClient.awaitSabImportFailure(releaseName)

    private fun waitForCompletion(releaseName: String) =
        webTestClient.awaitSabImportCompletion(releaseName)
}
