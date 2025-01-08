package io.skjaere.debridav.circuitbreaker

import io.ktor.util.collections.ConcurrentMap
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class CircuitBreakerService {
    private val circuitBreakerMap: ConcurrentMap<String, Instant> = ConcurrentMap()

    fun openCircuitBreaker(name: String, duration: Duration) {
        circuitBreakerMap[name] = Instant.now().plus(duration)
    }

    fun checkCircuitBreaker(name: String): Boolean {
        circuitBreakerMap[name]?.let {
            if (it.isBefore(Instant.now())) {
                return true
            } else {
                circuitBreakerMap.remove(name)
                return false
            }

        } ?: return false
    }
}
