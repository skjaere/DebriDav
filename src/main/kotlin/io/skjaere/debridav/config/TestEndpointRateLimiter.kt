package io.skjaere.debridav.config

import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.stereotype.Component
import java.time.Duration

// Per-user budget for the /api/v1/config/test/** and /api/v1/config/nntp-pools/test
// endpoints. These endpoints accept caller-supplied host:port:user:pass and trigger
// outbound auth attempts against any host — even with JWT auth on the calling side,
// a compromised session could be used to probe arbitrary upstreams. The limiter caps
// each authenticated user to a sensible burst (5/min by default) so a stolen token
// can't be turned into a high-rate auth oracle. Anonymous / unauthenticated callers
// share a single bucket so the limit applies even when no SecurityContext is set.
//
// Per-user (not per-IP) because behind a reverse proxy the IP is often the proxy's.
@Component
class TestEndpointRateLimiter(
    private val rateLimiterRegistry: RateLimiterRegistry,
) {
    @Suppress("MagicNumber")
    private val config: RateLimiterConfig = RateLimiterConfig.custom()
        .limitRefreshPeriod(Duration.ofMinutes(1))
        .limitForPeriod(5)
        .timeoutDuration(Duration.ZERO)
        .build()

    /** Returns true if the call is allowed; false if the user is over budget. */
    fun tryAcquire(user: String): Boolean {
        val limiter = rateLimiterRegistry.rateLimiter("config-test-$user", config)
        return limiter.acquirePermission()
    }
}
