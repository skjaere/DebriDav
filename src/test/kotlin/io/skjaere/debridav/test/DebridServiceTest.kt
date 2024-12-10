package io.skjaere.debridav.test

import io.ktor.utils.io.errors.IOException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.skjaere.debridav.LinkCheckService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridService
import io.skjaere.debridav.debrid.client.premiumize.PremiumizeClient
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.debrid.model.SuccessfulIsCachedResult
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.test.integrationtest.config.TestContextInitializer
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class DebridServiceTest {
    private val premiumizeClient = mockk<PremiumizeClient>()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1730477942L), ZoneId.systemDefault())
    private val realDebridClient = mockk<RealDebridClient>()
    private val linkCheckService = mockk<LinkCheckService>()
    private val debridClients = listOf(realDebridClient, premiumizeClient)
    private val debridavConfiguration = DebridavConfiguration(
        mountPath = "${TestContextInitializer.BASE_PATH}/debridav",
        debridClients = listOf(DebridProvider.REAL_DEBRID, DebridProvider.PREMIUMIZE),
        downloadPath = "${TestContextInitializer.BASE_PATH}/downloads",
        cacheLocalDebridFilesThresholdMb = 2,
        filePath = "${TestContextInitializer.BASE_PATH}/files",
        retriesOnProviderError = 3,
        waitAfterNetworkError = Duration.ofMillis(1000),
        delayBetweenRetries = Duration.ofMillis(1000),
        waitAfterMissing = Duration.ofMillis(1000),
        waitAfterProviderError = Duration.ofMillis(1000),
        readTimeoutMilliseconds = 1000,
        connectTimeoutMilliseconds = 1000,
        waitAfterClientError = Duration.ofMillis(1000),
        shouldDeleteNonWorkingFiles = true,
        torrentLifetime = Duration.ofMinutes(1)
    )
    private var file: File? = null

    private val fileService = spyk(FileService(debridavConfiguration))

    private val underTest = DebridService(
        debridavConfiguration = debridavConfiguration,
        debridClients = debridClients,
        fileService = fileService,
        linkCheckService = linkCheckService,
        clock = clock
    )

    @BeforeEach
    fun setup() {
        every { premiumizeClient.getProvider() } returns DebridProvider.PREMIUMIZE
        every { realDebridClient.getProvider() } returns DebridProvider.REAL_DEBRID
        every { fileService.getDebridFileContents(any()) } returns debridFileContents.deepCopy()
        if (file == null) {
            file = File.createTempFile("debridav", ".tmp")
        }
        file?.writeText("")
    }

    @AfterEach
    fun teardown() {
        file?.delete()
    }

    @Test
    fun thatIsCachedReturnsTrueWhenOneDebridClientReturnsTrue() {
        // given
        coEvery { premiumizeClient.isCached(eq(MAGNET)) } returns false
        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns true

        // when
        val result = underTest.isCached(MAGNET)

        // then
        assertEquals(
            result, listOf(
                SuccessfulIsCachedResult(true, DebridProvider.REAL_DEBRID),
                SuccessfulIsCachedResult(false, DebridProvider.PREMIUMIZE),
            )
        )
    }

    @Test
    fun thatIsCachedReturnsFalseWhenTwoDebridClientsReturnsFalse() {
        // given
        coEvery { premiumizeClient.isCached(eq(MAGNET)) } returns false
        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns false

        // when
        val result = underTest.isCached(MAGNET)

        // then
        assertEquals(
            result, listOf(
                SuccessfulIsCachedResult(false, DebridProvider.REAL_DEBRID),
                SuccessfulIsCachedResult(false, DebridProvider.PREMIUMIZE),
            )
        )
    }

    @Test
    fun thatGettingDebridFileContentsByMagnetWorks() {
        // given
        mockIsCached()
        coEvery { premiumizeClient.getCachedFiles(eq(MAGNET)) } returns listOf(premiumizeCachedFile)
        coEvery { realDebridClient.getCachedFiles(eq(MAGNET)) } returns listOf(realDebridCachedFile)

        // when
        val result = runBlocking { underTest.addMagnet(MAGNET) }
        assertEquals(debridFileContents, result.first())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thatGettingCachedLinksFromDebridProvidersIsDoneConcurrently() {
        // given
        val testScope = TestScope()
        coEvery { premiumizeClient.isCached(eq(MAGNET)) } coAnswers { true }
        coEvery { realDebridClient.isCached(eq(MAGNET)) } coAnswers { true }

        coEvery { premiumizeClient.getCachedFiles(eq(MAGNET)) } coAnswers {
            coroutineScope {
                delay(1000)
                listOf(premiumizeCachedFile)
            }
        }

        coEvery { realDebridClient.getCachedFiles(eq(MAGNET)) } coAnswers {
            coroutineScope {
                delay(1000)
                listOf(realDebridCachedFile)
            }
        }

        // when
        testScope.launch {
            underTest.addMagnet(MAGNET)
        }
        testScope.advanceUntilIdle()

        // then
        assertEquals(1000, testScope.currentTime)
    }

    @Test
    fun thatGettingDebridFileContentsByMagnetWorksWhenMissingFromPremiumize() {
        // given
        coEvery { premiumizeClient.isCached(eq(MAGNET)) } returns false
        coEvery { premiumizeClient.getCachedFiles(eq(MAGNET)) } returns listOf()

        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns true
        coEvery { realDebridClient.getCachedFiles(eq(MAGNET)) } returns listOf(realDebridCachedFile)

        // when
        val result = runBlocking { underTest.addMagnet(MAGNET) }

        // then
        val expectedDebridFileContents = debridFileContents.deepCopy()
        expectedDebridFileContents.debridLinks = mutableListOf(
            debridFileContents.debridLinks.first(),
            MissingFile(DebridProvider.PREMIUMIZE, Instant.now(clock).toEpochMilli())
        )
        assertEquals(expectedDebridFileContents, result.first())
    }

    @Test
    fun thatGettingDebridFileContentsByMagnetWorksWhenErrorFromPremiumize() {
        // given
        coEvery { premiumizeClient.getCachedFiles(eq(MAGNET)) } throws mock<DebridProviderError>()
        coEvery { realDebridClient.getCachedFiles(eq(MAGNET)) } returns listOf(realDebridCachedFile)

        // when
        val result = runBlocking { underTest.addMagnet(MAGNET) }

        // then
        val expectedDebridFileContents = debridFileContents.deepCopy()
        expectedDebridFileContents.debridLinks = mutableListOf(
            debridFileContents.debridLinks.first(),
            ProviderError(DebridProvider.PREMIUMIZE, Instant.now(clock).toEpochMilli())
        )
        assertEquals(expectedDebridFileContents, result.first())
    }

    @Test
    fun thatGettingDebridFileContentsByMagnetIsRetriedWhenErrorFromPremiumize() {
        // given
        coEvery { premiumizeClient.getCachedFiles(eq(MAGNET)) } throws mock<IOException>()
        coEvery { realDebridClient.getCachedFiles(eq(MAGNET)) } returns listOf(realDebridCachedFile)

        // when
        runTest {
            underTest.addMagnet(MAGNET)
        }

        // then
        coVerify(exactly = 4) { premiumizeClient.getCachedFiles(eq(MAGNET)) }
    }

    @Test
    fun thatGetCheckedLinksRespectsDebridProviderOrdering() {
        // given
        coEvery { linkCheckService.isLinkAlive(any()) } returns true

        // when
        val result = runBlocking {
            underTest.getCheckedLinks(file!!).firstOrNull()
        }

        assertEquals(result, realDebridCachedFile)
    }

    @Test
    fun thatCachedFileWithNonWorkingLinkGetsRefreshed() {
        // given
        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns true
        coEvery { linkCheckService.isLinkAlive(eq(realDebridCachedFile)) } returns false

        val freshCachedFile = CachedFile(
            path = "/foo/bar.mkv",
            provider = DebridProvider.REAL_DEBRID,
            size = 100L,
            link = "http://test.test/updated_bar.mkv",
            lastChecked = 100,
            params = mapOf(),
            mimeType = "video/mkv"
        )

        coEvery { realDebridClient.getCachedFiles(eq(debridFileContents.magnet)) } returns listOf(freshCachedFile)

        // when
        val result = runBlocking {
            underTest.getCheckedLinks(file!!).firstOrNull()
        }

        assertEquals(freshCachedFile, result)
    }

    @Test
    fun thatCachedFileWithNonWorkingLinkAndIsNotCachedGetsReplacedWithMissingLink() {
        // given
        mockIsNotCached()
        coEvery { linkCheckService.isLinkAlive(eq(realDebridCachedFile)) } returns false
        coEvery { linkCheckService.isLinkAlive(eq(premiumizeCachedFile)) } returns true
        coEvery { realDebridClient.getCachedFiles(eq(debridFileContents.magnet)) } returns listOf()

        // when
        val result = runBlocking {
            underTest.getCheckedLinks(file!!).firstOrNull()
        }
        val updatedFileContents = Json.decodeFromString<DebridFileContents>(file!!.readText())

        // then
        assertEquals(result, premiumizeCachedFile)
        assertEquals(
            DebridFileContents(
                debridFileContents.originalPath,
                debridFileContents.size,
                debridFileContents.modified,
                debridFileContents.magnet,
                mutableListOf(
                    MissingFile(DebridProvider.REAL_DEBRID, Instant.now(clock).toEpochMilli()),
                    premiumizeCachedFile
                )
            ),
            updatedFileContents
        )
    }

    @Test
    fun thatCachedFileWithNonWorkingLinkAndIsCachedGetsReplacedWithProviderErrorOnError() {
        // given
        mockIsCached()
        coEvery { linkCheckService.isLinkAlive(eq(realDebridCachedFile)) } returns false
        coEvery { linkCheckService.isLinkAlive(eq(premiumizeCachedFile)) } returns true
        coEvery { realDebridClient.getCachedFiles(eq(debridFileContents.magnet)) } throws mock<DebridProviderError>()

        // when
        val result = runBlocking {
            underTest.getCheckedLinks(file!!).firstOrNull()
        }
        val updatedFileContents = Json.decodeFromString<DebridFileContents>(file!!.readText())

        // then
        assertEquals(premiumizeCachedFile, result)
        assertEquals(
            DebridFileContents(
                debridFileContents.originalPath,
                debridFileContents.size,
                debridFileContents.modified,
                debridFileContents.magnet,
                mutableListOf(
                    ProviderError(DebridProvider.REAL_DEBRID, Instant.now(clock).toEpochMilli()),
                    premiumizeCachedFile
                )
            ),
            updatedFileContents
        )
    }

    @Test
    fun thatCachedFileWithNonWorkingLinkAndIsCachedDoesNotGetReplacedOnNetworkError() {
        // given
        mockIsCached()
        coEvery { linkCheckService.isLinkAlive(eq(realDebridCachedFile)) } returns false
        coEvery { linkCheckService.isLinkAlive(eq(premiumizeCachedFile)) } returns true
        coEvery { realDebridClient.getCachedFiles(eq(debridFileContents.magnet)) } throws IOException()

        // when
        val result = runBlocking {
            underTest.getCheckedLinks(file!!).firstOrNull()
        }
        val updatedFileContents = Json.decodeFromString<DebridFileContents>(file!!.readText())

        // then
        assertEquals(premiumizeCachedFile, result)
        assertEquals(debridFileContents, updatedFileContents)
    }

    private fun mockIsNotCached() {
        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns false
        coEvery { premiumizeClient.isCached(eq(MAGNET)) } returns false
    }

    private fun mockIsCached() {
        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns true
        coEvery { premiumizeClient.isCached(eq(MAGNET)) } returns true
    }

    @Test
    fun thatDebridLinkGetsAddedToDebridFileContentsWhenProviderIsMissing() {
        // given
        coEvery { realDebridClient.getCachedFiles(eq(MAGNET)) } returns listOf(realDebridCachedFile)
        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns true
        coEvery { linkCheckService.isLinkAlive(any()) } returns true

        val debridFileContentsWithoutRealDebridLink = debridFileContents.deepCopy()
        debridFileContentsWithoutRealDebridLink.debridLinks.removeFirst()

        // when
        val result = runBlocking { underTest.getCheckedLinks(file!!).first() }

        // then
        assertEquals(result.provider, DebridProvider.REAL_DEBRID)
    }

    @Test
    fun thatMissingLinkGetsReplacedWithCachedLinkWhenProviderHasFile() {
        // given
        coEvery { realDebridClient.getCachedFiles(eq(MAGNET)) } returns listOf(realDebridCachedFile)
        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns true
        coEvery { linkCheckService.isLinkAlive(eq(realDebridCachedFile)) } returns true

        val debridFileContentsWithMissingRealDebridLink = debridFileContents.deepCopy()
        debridFileContentsWithMissingRealDebridLink.debridLinks = mutableListOf(
            MissingFile(DebridProvider.REAL_DEBRID, Instant.now(clock).minus(25, ChronoUnit.HOURS).toEpochMilli()),
            debridFileContents.debridLinks.last()
        )
        every { fileService.getDebridFileContents(any()) } returns debridFileContents.deepCopy()

        // when
        val result = runBlocking { underTest.getCheckedLinks(file!!).first() }

        // then
        assertEquals(result.provider, DebridProvider.REAL_DEBRID)
    }

    @Test
    fun thatRecentlyCheckedDebridFileDoesNotGetReChecked() {
        // given
        mockIsCached()
        val debridFileContentsWithMissingRealDebridLink = debridFileContents.deepCopy()
        debridFileContentsWithMissingRealDebridLink.debridLinks = mutableListOf(
            MissingFile(DebridProvider.REAL_DEBRID, Instant.now(clock).minus(1, ChronoUnit.HOURS).toEpochMilli()),
            debridFileContents.debridLinks.last()
        )
        every { fileService.getDebridFileContents(any()) } returns debridFileContentsWithMissingRealDebridLink.deepCopy()
        coEvery { linkCheckService.isLinkAlive(eq(premiumizeCachedFile)) } returns true

        // when
        val result = runBlocking { underTest.getCheckedLinks(file!!).first() }

        // then
        assertEquals(DebridProvider.PREMIUMIZE, result.provider)
    }

    private fun DebridFileContents.deepCopy() =
        Json.decodeFromString<DebridFileContents>(
            Json.encodeToString(DebridFileContents.serializer(), this)
        )
}
