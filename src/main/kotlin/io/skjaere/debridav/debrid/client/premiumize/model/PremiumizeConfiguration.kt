package io.skjaere.debridav.debrid.client.premiumize.model

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

private const val PERIOD_LIMIT = 999

@Configuration
class PremiumizeConfiguration {
    @Bean
    fun premiumizeRateLimiter(rateLimiterRegistry: RateLimiterRegistry): RateLimiter {
        val rateLimiterConfig = RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .limitForPeriod(PERIOD_LIMIT)
            .timeoutDuration(Duration.ofMillis(1))
            .build()
        rateLimiterRegistry.rateLimiter("PREMIUMIZE", rateLimiterConfig)
        return rateLimiterRegistry.rateLimiter("PREMIUMIZE")
    }
}
