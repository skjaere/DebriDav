package io.skjaere.debridav.test

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.arrs.client.RadarrApiClient
import io.skjaere.debridav.arrs.client.SonarrApiClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrServiceTest {
    private val sonarrApiClient = mockk<SonarrApiClient>()
    private val underTest = ArrService(listOf(sonarrApiClient))

    @Test
    fun thatDeleteFileAndSearchCallsClient() = runTest {
        //given
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        coEvery { sonarrApiClient.deleteFileAndSearch(eq("test-item"), any()) } returns true

        //when
        underTest.deleteFileAndSearch("test-item", setOf("/data/downloads/x/test-item"), "tv-sonarr")

        //then
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch(eq("test-item"), any()) }
    }

    @Test
    fun thatDeleteFileAndSearchDoesNothingForUnmappedCategory() = runTest {
        //given
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"

        //when
        underTest.deleteFileAndSearch("test-item", setOf("/data/downloads/x/test-item"), "unknown-category")

        //then
        coVerify(exactly = 0) { sonarrApiClient.deleteFileAndSearch(any(), any()) }
    }

    @Test
    fun thatFanoutCallsClientsInOrderUntilOneRecognisesItem() = runTest {
        //given — sonarr (first in list) doesn't recognise the movie; radarr (second) does.
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.deleteFileAndSearch(any(), any()) } returns false
        coEvery { radarrApiClient.deleteFileAndSearch(any(), any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("Some.Movie.2024.1080p.mkv", setOf("/data/downloads/x.mkv"))

        //then — fanout reaches radarr because sonarr returned false; both invoked, in order.
        assertTrue(handled, "expected fanout to report handled when radarr returned true")
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch("Some.Movie.2024.1080p.mkv", any()) }
        coVerify(exactly = 1) { radarrApiClient.deleteFileAndSearch("Some.Movie.2024.1080p.mkv", any()) }
    }

    @Test
    fun thatFanoutShortCircuitsAfterFirstMatch() = runTest {
        //given — sonarr (first in list) handles the item, radarr should not be tried.
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.deleteFileAndSearch(any(), any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("Some.Show.S01E01.mkv", setOf("/data/downloads/x.mkv"))

        //then
        assertTrue(handled)
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch("Some.Show.S01E01.mkv", any()) }
        coVerify(exactly = 0) { radarrApiClient.deleteFileAndSearch(any(), any()) }
    }

    @Test
    fun thatFanoutReturnsFalseWhenNoClientRecognisesItem() = runTest {
        //given
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.deleteFileAndSearch(any(), any()) } returns false
        coEvery { radarrApiClient.deleteFileAndSearch(any(), any()) } returns false
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("unrecognised.file.mkv", setOf("/data/downloads/x.mkv"))

        //then
        assertFalse(handled)
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch(any(), any()) }
        coVerify(exactly = 1) { radarrApiClient.deleteFileAndSearch(any(), any()) }
    }

    @Test
    fun thatFanoutSurvivesAClientThrowing() = runTest {
        //given — sonarr throws (e.g. the arr is offline), radarr handles it
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.deleteFileAndSearch(any(), any()) } throws RuntimeException("sonarr down")
        coEvery { radarrApiClient.deleteFileAndSearch(any(), any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("Some.Movie.mkv", setOf("/data/downloads/x.mkv"))

        //then — the throwing client must not block the others
        assertTrue(handled)
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch(any(), any()) }
        coVerify(exactly = 1) { radarrApiClient.deleteFileAndSearch(any(), any()) }
    }

    @Test
    fun thatFanoutReturnsFalseWhenNoArrsConfigured() = runTest {
        //given
        val service = ArrService(emptyList())

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("anything.mkv", setOf("/data/downloads/x.mkv"))

        //then
        assertFalse(handled)
    }

    @Test
    fun thatRefreshMonitoredDownloadsRoutesToMatchingClient() = runTest {
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        coEvery { sonarrApiClient.refreshMonitoredDownloads() } returns true

        underTest.refreshMonitoredDownloads("tv-sonarr")

        coVerify(exactly = 1) { sonarrApiClient.refreshMonitoredDownloads() }
    }

    @Test
    fun thatRefreshMonitoredDownloadsIsNoOpForUnknownCategory() = runTest {
        // No fanout: if the category doesn't match any configured arr we know the
        // content wasn't added by an arr, so there's nothing to nudge.
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"

        underTest.refreshMonitoredDownloads("manual")

        coVerify(exactly = 0) { sonarrApiClient.refreshMonitoredDownloads() }
    }

    @Test
    fun thatRefreshMonitoredDownloadsSwallowsClientExceptions() = runTest {
        // A refresh failure mustn't disrupt the caller (an import path that just
        // committed) — caller intent is fire-and-forget.
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        coEvery { sonarrApiClient.refreshMonitoredDownloads() } throws RuntimeException("sonarr down")

        underTest.refreshMonitoredDownloads("tv-sonarr") // must not throw

        coVerify(exactly = 1) { sonarrApiClient.refreshMonitoredDownloads() }
    }

    @Test
    fun thatBlocklistFanoutCallsClientsInOrderWhenFirstReturnsFalse() = runTest {
        //given — sonarr (first) doesn't have the hash; radarr (second) does.
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.blocklist(any()) } returns false
        coEvery { radarrApiClient.blocklist(any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.blocklistAcrossAllArrs("abcdef0123456789abcdef0123456789abcdef01")

        //then — fanout reaches radarr because sonarr returned false; both invoked.
        assertTrue(handled)
        coVerify(exactly = 1) { sonarrApiClient.blocklist("abcdef0123456789abcdef0123456789abcdef01") }
        coVerify(exactly = 1) { radarrApiClient.blocklist("abcdef0123456789abcdef0123456789abcdef01") }
    }

    @Test
    fun thatBlocklistFanoutShortCircuitsAfterFirstMatch() = runTest {
        //given — sonarr (first) handles it, radarr should not be called.
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.blocklist(any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.blocklistAcrossAllArrs("hash")

        //then
        assertTrue(handled)
        coVerify(exactly = 1) { sonarrApiClient.blocklist("hash") }
        coVerify(exactly = 0) { radarrApiClient.blocklist(any()) }
    }

    @Test
    fun thatBlocklistFanoutReturnsFalseWhenNoArrRecognisedHash() = runTest {
        //given — neither arr knows the hash.
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.blocklist(any()) } returns false
        coEvery { radarrApiClient.blocklist(any()) } returns false
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.blocklistAcrossAllArrs("hash")

        //then
        assertFalse(handled)
        coVerify(exactly = 1) { sonarrApiClient.blocklist("hash") }
        coVerify(exactly = 1) { radarrApiClient.blocklist("hash") }
    }

    @Test
    fun thatBlocklistFanoutSurvivesAClientThrowing() = runTest {
        //given — sonarr throws, radarr handles it.
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.blocklist(any()) } throws RuntimeException("sonarr down")
        coEvery { radarrApiClient.blocklist(any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when — must not throw, must still call radarr
        val handled = service.blocklistAcrossAllArrs("hash")

        //then
        assertTrue(handled)
        coVerify(exactly = 1) { sonarrApiClient.blocklist("hash") }
        coVerify(exactly = 1) { radarrApiClient.blocklist("hash") }
    }
}
