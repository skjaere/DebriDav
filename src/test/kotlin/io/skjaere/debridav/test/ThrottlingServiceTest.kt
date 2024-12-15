/*
package io.skjaere.debridav.test


import io.mockk.spyk
import io.skjaere.debridav.ThrottlingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ThrottlingServiceTest {

    private val logger = LoggerFactory.getLogger(ThrottlingServiceTest::class.java)
    private val clock = spyk<Clock>(Clock.System)
    val instant = clock.now()
    private val underTest = ThrottlingService(clock)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun test() {
        val testScope = TestScope()

        testScope.launch {
            underTest.throttle(
                "test",
                2,
                4
            ) {
                logger.info("first: ${(1730477942L - clock.instant().toEpochMilli())}")
            }
        }
        testScope.launch {
            underTest.throttle(
                "test",
                2,
                4
            ) {
                logger.info("second: ${(1730477942L - clock.instant().toEpochMilli())}")
            }
        }
        testScope.launch {
            underTest.throttle(
                "test",
                2,
                4
            ) {
                underTest.openCircuitBreaker("test", 2000)
                logger.info("third: ${(1730477942L - clock.instant().toEpochMilli())}")
            }
        }.invokeOnCompletion {
            testScope.launch {
                delay(100)
                underTest.throttle(
                    "test",
                    2,
                    4
                ) {
                    logger.info("fourth: ${(1730477942L - clock.instant().toEpochMilli())}")
                }
            }
            testScope.launch {
                delay(100)
                underTest.throttle(
                    "test",
                    2,
                    4
                ) {
                    logger.info("fifth: ${(1730477942L - clock.instant().toEpochMilli())}")
                }
            }
        }
        //testScope.launch { delay(100) }

        testScope.advanceUntilIdle()
        val time = testScope.currentTime

        // then
        assertEquals(1000, testScope.currentTime)
    }


}*/
