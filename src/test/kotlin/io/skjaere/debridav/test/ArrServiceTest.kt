package io.skjaere.debridav.test

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
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
        coEvery { sonarrApiClient.deleteFileAndSearch(eq("test-item")) } returns true

        //when
        underTest.deleteFileAndSearch("test-item", "tv-sonarr")

        //then
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch(eq("test-item")) }
    }

    @Test
    fun thatDeleteFileAndSearchDoesNothingForUnmappedCategory() = runTest {
        //given
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"

        //when
        underTest.deleteFileAndSearch("test-item", "unknown-category")

        //then
        coVerify(exactly = 0) { sonarrApiClient.deleteFileAndSearch(any()) }
    }

    @Test
    fun thatFanoutCallsClientsInOrderUntilOneRecognisesItem() = runTest {
        //given — sonarr (first in list) doesn't recognise the movie; radarr (second) does.
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.deleteFileAndSearch(any()) } returns false
        coEvery { radarrApiClient.deleteFileAndSearch(any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("Some.Movie.2024.1080p.mkv")

        //then — fanout reaches radarr because sonarr returned false; both invoked, in order.
        assertTrue(handled, "expected fanout to report handled when radarr returned true")
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch("Some.Movie.2024.1080p.mkv") }
        coVerify(exactly = 1) { radarrApiClient.deleteFileAndSearch("Some.Movie.2024.1080p.mkv") }
    }

    @Test
    fun thatFanoutShortCircuitsAfterFirstMatch() = runTest {
        //given — sonarr (first in list) handles the item, radarr should not be tried.
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.deleteFileAndSearch(any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("Some.Show.S01E01.mkv")

        //then
        assertTrue(handled)
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch("Some.Show.S01E01.mkv") }
        coVerify(exactly = 0) { radarrApiClient.deleteFileAndSearch(any()) }
    }

    @Test
    fun thatFanoutReturnsFalseWhenNoClientRecognisesItem() = runTest {
        //given
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.deleteFileAndSearch(any()) } returns false
        coEvery { radarrApiClient.deleteFileAndSearch(any()) } returns false
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("unrecognised.file.mkv")

        //then
        assertFalse(handled)
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch(any()) }
        coVerify(exactly = 1) { radarrApiClient.deleteFileAndSearch(any()) }
    }

    @Test
    fun thatFanoutSurvivesAClientThrowing() = runTest {
        //given — sonarr throws (e.g. the arr is offline), radarr handles it
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.deleteFileAndSearch(any()) } throws RuntimeException("sonarr down")
        coEvery { radarrApiClient.deleteFileAndSearch(any()) } returns true
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("Some.Movie.mkv")

        //then — the throwing client must not block the others
        assertTrue(handled)
        coVerify(exactly = 1) { sonarrApiClient.deleteFileAndSearch(any()) }
        coVerify(exactly = 1) { radarrApiClient.deleteFileAndSearch(any()) }
    }

    @Test
    fun thatFanoutReturnsFalseWhenNoArrsConfigured() = runTest {
        //given
        val service = ArrService(emptyList())

        //when
        val handled = service.deleteFileAndSearchAcrossAllArrs("anything.mkv")

        //then
        assertFalse(handled)
    }

    @Test
    fun thatBlocklistFanoutCallsEveryClient() = runTest {
        //given
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.blocklist(any()) } just Runs
        coEvery { radarrApiClient.blocklist(any()) } just Runs
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when
        service.blocklistAcrossAllArrs("abcdef0123456789abcdef0123456789abcdef01")

        //then
        coVerify(exactly = 1) { sonarrApiClient.blocklist("abcdef0123456789abcdef0123456789abcdef01") }
        coVerify(exactly = 1) { radarrApiClient.blocklist("abcdef0123456789abcdef0123456789abcdef01") }
    }

    @Test
    fun thatBlocklistFanoutSurvivesAClientThrowing() = runTest {
        //given
        val radarrApiClient = mockk<RadarrApiClient>()
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        every { radarrApiClient.getCategory() } returns "movies"
        coEvery { sonarrApiClient.blocklist(any()) } throws RuntimeException("sonarr down")
        coEvery { radarrApiClient.blocklist(any()) } just Runs
        val service = ArrService(listOf(sonarrApiClient, radarrApiClient))

        //when — must not throw, must still call radarr
        service.blocklistAcrossAllArrs("hash")

        //then
        coVerify(exactly = 1) { sonarrApiClient.blocklist(any()) }
        coVerify(exactly = 1) { radarrApiClient.blocklist(any()) }
    }
}
