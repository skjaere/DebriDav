package io.skjaere.debridav.debrid.client.realdebrid

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration


private const val NUMBER_OF_REQUESTS_IN_WINDOW = 249
private const val WINDOW_DURATION_MINUTES = 1L

@Configuration
class RealDebridConfiguration {
    @Bean
    @ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('real_debrid')}")
    fun realDebridRateLimiter(rateLimiterRegistry: RateLimiterRegistry): RateLimiter {
        val rateLimiterConfig = RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofMillis(1))
            .limitForPeriod(NUMBER_OF_REQUESTS_IN_WINDOW)
            .timeoutDuration(Duration.ofMinutes(WINDOW_DURATION_MINUTES))
            .build()
        rateLimiterRegistry.rateLimiter("REAL_DEBRID", rateLimiterConfig)
        return rateLimiterRegistry.rateLimiter("REAL_DEBRID")
    }
}

