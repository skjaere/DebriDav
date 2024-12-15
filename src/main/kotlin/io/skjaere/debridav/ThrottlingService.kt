package io.skjaere.debridav

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ThrottlingService {
    companion object {
        const val CACHE_SIZE = 10L
    }

    private val logger = LoggerFactory.getLogger(ThrottlingService::class.java)
    private val circuitBreakers = ConcurrentHashMap<String, Long>()
    private val circuitBreakerCache: LoadingCache<String, Mutex> = CacheBuilder.newBuilder()
        .maximumSize(CACHE_SIZE)
        .build(CacheLoader.from { _ -> Mutex() })

    suspend fun <T> throttle(
        key: String,
        block: suspend CoroutineScope.() -> T
    ): T = coroutineScope {
        circuitBreakerCache.get(key).withLock {
            if (circuitBreakers.containsKey(key)) {
                logger.info("Circuit breaker open for $key for ${circuitBreakers[key]}ms")
                delay(circuitBreakers[key]!!)
                circuitBreakers.remove(key)
            }
        }
        block.invoke(this)
    }

    suspend fun openCircuitBreaker(key: String, waitMs: Long) {
        circuitBreakerCache.get(key).withLock {
            val timeToWait = if (circuitBreakers.containsKey(key)) {
                listOf(circuitBreakers[key]!!, waitMs).maxOf { it }
            } else waitMs
            logger.debug("Opening circuit breaker for $key for $waitMs ms")
            circuitBreakers[key] = timeToWait
        }
    }
}
