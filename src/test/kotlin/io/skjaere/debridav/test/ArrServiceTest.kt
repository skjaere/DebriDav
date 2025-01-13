package io.skjaere.debridav.test


import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.arrs.client.SonarrApiClient
import io.skjaere.debridav.arrs.client.models.HistoryResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import kotlin.test.assertEquals

class ArrServiceTest {
    val sonarrApiClient = mockk<SonarrApiClient>()
    val tpte = getThreadpoolExecutor()
    val underTest = ArrService(listOf(sonarrApiClient), tpte)

    @Test
    fun thatArrIsToldThatDownloadFailed() {
        //given
        every { sonarrApiClient.getCategory() } returns "tv-sonarr"
        coEvery { sonarrApiClient.getItemIdFromName(eq("test-item")) } returns 1L
        coEvery { sonarrApiClient.history(eq(1L)) } returns HistoryResponse(
            listOf(
                HistoryResponse.HistoryRecord(
                    "grabbed",
                    3L
                )
            )
        )
        coEvery { sonarrApiClient.failed(eq(3L)) } just Runs

        //when
        runTest {
            underTest.markDownloadAsFailed("test-item", "tv-sonarr")
        }

        //then
        coVerify(exactly = 1) { sonarrApiClient.failed(eq(3L)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thatItWaitsUntilItemIsPresentInArr() {
        //given
        val testScope = TestScope()

            every { sonarrApiClient.getCategory() } returns "tv-sonarr"
            coEvery { sonarrApiClient.getItemIdFromName(eq("test-item")) } returns null
            testScope.launch {
                delay(30L)
                coEvery { sonarrApiClient.getItemIdFromName(eq("test-item")) } returns 1L
            }
            coEvery { sonarrApiClient.history(eq(1L)) } returns HistoryResponse(
                listOf(
                    HistoryResponse.HistoryRecord(
                        "grabbed",
                        3L
                    )
                )
            )
            coEvery { sonarrApiClient.failed(eq(3L)) } just Runs

            //when
            testScope.launch { underTest.markDownloadAsFailed("test-item", "tv-sonarr") }


        //then
        testScope.advanceUntilIdle()
        assertEquals(30L, testScope.currentTime)
        coVerify(exactly = 1) { sonarrApiClient.failed(eq(3L)) }
    }

    //TODO: test that it waits

    fun getThreadpoolExecutor(): ThreadPoolTaskExecutor {
        val tpte = ThreadPoolTaskExecutor()
        tpte.corePoolSize = 1
        tpte.initialize()
        return tpte
    }
}
