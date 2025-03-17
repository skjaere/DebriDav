package io.skjaere.debridav

import kotlin.math.absoluteValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant


class RateLimiter(
    private val timeWindowDuration: Duration,
    private val numberOfRequestsInWindow: Int,
    private val name: String
) {
    private val lock = Mutex()
    private val logger = LoggerFactory.getLogger(RateLimiter::class.java)
    private val movingTimeWindow = mutableListOf<Instant>()

    suspend fun <T> doWithRateLimit(
        block: suspend () -> T
    ): T {
        lock.withLock {
            if (shouldThrottle()) {
                val timeToWait = getMsToWait(timeWindowDuration, numberOfRequestsInWindow)
                logger.info("waiting for $timeToWait for $name")
                delay(timeToWait)
            }
            return block.invoke()
        }
    }

    private fun shouldThrottle(numberOfRequestsInTimespan: Int, timeWindow: MutableList<Instant>): Boolean {
        timeWindow.removeIf { it.isBefore(Instant.now().minus(timeWindowDuration)) }
        return movingTimeWindow.size >= numberOfRequestsInTimespan
    }

    private fun shouldThrottle(): Boolean = shouldThrottle(
        numberOfRequestsInWindow, movingTimeWindow
    )

    private fun getMsToWait(
        windowDuration: Duration,
        numberOfRequestsInTimespan: Int
    ): Long {
        val copiedTimeWindow = movingTimeWindow.sorted().toMutableList()
        var instantToWaitFor = copiedTimeWindow.last()
        do {
            instantToWaitFor = copiedTimeWindow.first()
            copiedTimeWindow.removeFirst()
        } while (shouldThrottle(numberOfRequestsInTimespan, copiedTimeWindow))
        return Duration.between(
            instantToWaitFor.plus(windowDuration),
            Instant.now()
        ).toMillis().absoluteValue
    }
}
