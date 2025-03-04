package io.skjaere.debridav

import kotlin.math.absoluteValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class RateLimitingService {
    private val timeWindows = mutableMapOf<String, MutableList<Instant>>()
    private val locks = mutableMapOf<String, Mutex>()
    private val logger = LoggerFactory.getLogger(RateLimitingService::class.java)

    suspend fun <T> doWithRateLimit(
        key: String,
        duration: Duration,
        numberOfRequestsInTimespan: Int,
        block: suspend () -> T
    ): T {
        val lock: Mutex = if (locks.containsKey(key)) {
            locks[key]!!
        } else {
            locks[key] = Mutex()
            locks[key]!!
        }
        lock.withLock {
            if (shouldThrottle(key, duration, numberOfRequestsInTimespan)) {
                val timeToWait = getMsToWait(key, duration, numberOfRequestsInTimespan)
                logger.info("waiting for ${timeToWait} for key $key")
                delay(timeToWait)
            }
            if (timeWindows.containsKey(key)) {
                timeWindows[key]!!.add(Instant.now())
            } else {
                timeWindows[key] = mutableListOf(Instant.now())
            }

            return block.invoke()
        }
    }

    private fun shouldThrottle(key: String, windowDuration: Duration, numberOfRequestsInTimespan: Int): Boolean {
        return if (timeWindows.containsKey(key)) {
            shouldThrottle(windowDuration, numberOfRequestsInTimespan, timeWindows[key]!!)
        } else {
            false
        }
    }

    private fun shouldThrottle(
        windowDuration: Duration,
        numberOfRequestsInTimespan: Int,
        timeWindow: MutableList<Instant>
    ): Boolean {
        timeWindow.removeIf { it.isBefore(Instant.now().minus(windowDuration)) }
        return timeWindow.size >= numberOfRequestsInTimespan
    }

    fun getMsToWait(
        key: String,
        windowDuration: Duration,
        numberOfRequestsInTimespan: Int
    ): Long {
        timeWindows[key]!!.let { timeWindow ->
            val copiedTimeWindow = timeWindow.sorted().toMutableList()
            var instantToWaitFor = copiedTimeWindow.last()
            do {
                instantToWaitFor = copiedTimeWindow.first()
                copiedTimeWindow.removeFirst()
            } while (shouldThrottle(windowDuration, numberOfRequestsInTimespan, copiedTimeWindow))
            return Duration.between(
                instantToWaitFor.plus(windowDuration),
                Instant.now()
            ).toMillis().absoluteValue
        }
    }
}
