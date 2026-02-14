package io.skjaere.debridav

import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Resilience4jConfiguration {
    @Bean
    fun rateLimiterRegistry(): RateLimiterRegistry = RateLimiterRegistry.ofDefaults()

    @Bean
    fun retryRegistry(): RetryRegistry = RetryRegistry.ofDefaults()
}
