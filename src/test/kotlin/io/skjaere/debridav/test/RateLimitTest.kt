package io.skjaere.debridav.test

import io.mockk.every
import io.mockk.mockk
import io.skjaere.debridav.ratelimiter.TimeWindowRateLimiter
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

class RateLimitTest {
    val now: Instant = Instant.now()
    val clock: Clock = mockk<Clock>()
    val rateLimiter = TimeWindowRateLimiter(Duration.ofSeconds(10), 10, "test", clock)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `that rate limiter works`() {
        //given
        val testScope = TestScope()
        var runs = 1

        //when
        testScope.launch {
            repeat(100) { i ->
                every { clock.instant() } returns now.plusMillis(100L * i)
                rateLimiter.doWithRateLimit { runs++ }
                delay(100)
            }
        }

        //then
        testScope.advanceUntilIdle()
        assertEquals(419_500, testScope.currentTime)
    }
}
