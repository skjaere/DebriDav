/*
package io.skjaere.debridav.test

import io.ktor.utils.io.errors.IOException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.client.premiumize.PremiumizeClient
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.debrid.model.SuccessfulIsCachedResult
import io.skjaere.debridav.fs.DebridCachedContentFileContents
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.test.integrationtest.config.TestContextInitializer
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class DebridCachedContentTorrentServiceTest {
    private val premiumizeClient = mockk<PremiumizeClient>()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1730477942L), ZoneId.systemDefault())
    private val realDebridClient = mockk<RealDebridClient>()
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
        torrentLifetime = Duration.ofMinutes(1),
        waitBeforeStartStream = Duration.ofMillis(1)
    )

    private val fileService = mockk<FileService>()

    private val underTest = DebridCachedContentService(
        debridavConfiguration = debridavConfiguration,
        debridClients = debridClients,
        clock = clock
    )

    @BeforeEach
    fun setup() {
        every { premiumizeClient.getProvider() } returns DebridProvider.PREMIUMIZE
        every { realDebridClient.getProvider() } returns DebridProvider.REAL_DEBRID
        every { fileService.getDebridFileContents(any()) } returns debridFileContents.deepCopy()
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
        val result = runBlocking { underTest.addContent(MAGNET) }
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
            underTest.addContent(MAGNET)
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
        val result = runBlocking { underTest.addContent(MAGNET) }

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
        val result = runBlocking { underTest.addContent(MAGNET) }

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
            underTest.addContent(MAGNET)
        }

        // then
        coVerify(exactly = 4) { premiumizeClient.getCachedFiles(eq(MAGNET)) }
    }

    private fun mockIsCached() {
        coEvery { realDebridClient.isCached(eq(MAGNET)) } returns true
        coEvery { premiumizeClient.isCached(eq(MAGNET)) } returns true
    }

    private fun DebridCachedContentFileContents.deepCopy() =
        Json.decodeFromString<DebridCachedContentFileContents>(
            Json.encodeToString(DebridCachedContentFileContents.serializer(), this)
        )
}
*/
