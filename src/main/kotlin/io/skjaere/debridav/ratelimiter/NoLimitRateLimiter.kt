package io.skjaere.debridav.ratelimiter

class NoLimitRateLimiter : RateLimiter {
    override suspend fun <T> doWithRateLimit(block: suspend () -> T): T = block.invoke()
}
