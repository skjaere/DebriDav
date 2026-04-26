package io.skjaere.debridav.test

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.arrs.client.SonarrApiClient
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

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
}
