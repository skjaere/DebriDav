package io.skjaere.debridav.ratelimiter

interface RateLimiter {
    suspend fun <T> doWithRateLimit(
        block: suspend () -> T
    ): T
}
