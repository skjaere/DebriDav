package io.skjaere.debridav.ratelimiter

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

class TimeWindowRateLimiter(
    private val timeWindowDuration: Duration,
    private val numberOfRequestsInWindow: Int,
    private val name: String,
    private val clock: Clock
) : RateLimiter {
    private val lock = Mutex()
    private val logger = LoggerFactory.getLogger(TimeWindowRateLimiter::class.java)
    private val movingTimeWindow = mutableListOf<Instant>()

    constructor(
        timeWindowDuration: Duration,
        numberOfRequestsInWindow: Int,
        name: String
    ) : this(timeWindowDuration, numberOfRequestsInWindow, name, Clock.systemDefaultZone())

    override suspend fun <T> doWithRateLimit(
        block: suspend () -> T
    ): T {
        lock.withLock {
            if (shouldThrottle()) {
                val timeToWait = getMsToWait(timeWindowDuration)
                logger.info("waiting for $timeToWait for $name")
                delay(timeToWait)
            }
            movingTimeWindow.add(Instant.now(clock))
            return block.invoke()
        }
    }

    private fun shouldThrottle(numberOfRequestsInTimespan: Int, timeWindow: MutableList<Instant>): Boolean {
        timeWindow.removeIf { it.isBefore(Instant.now(clock).minus(timeWindowDuration)) }
        return timeWindow.size >= numberOfRequestsInTimespan
    }

    private fun shouldThrottle(): Boolean = shouldThrottle(
        numberOfRequestsInWindow, movingTimeWindow
    )

    private fun getMsToWait(windowDuration: Duration): Long {
        val earliestInstantInWindow = movingTimeWindow.sorted().toMutableList().first()
        val earliestInstantExpiry = earliestInstantInWindow.plus(windowDuration)
        return Duration.between(Instant.now(clock), earliestInstantExpiry).toMillis()
    }
}
