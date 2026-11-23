package io.skjaere.debridav

import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Resilience4jConfiguration {
    @Bean
    fun rateLimiterRegistry(meterRegistry: MeterRegistry): RateLimiterRegistry {
        val registry = RateLimiterRegistry.ofDefaults()
        TaggedRateLimiterMetrics.ofRateLimiterRegistry(registry).bindTo(meterRegistry)
        return registry
    }

    @Bean
    fun retryRegistry(meterRegistry: MeterRegistry): RetryRegistry {
        val registry = RetryRegistry.ofDefaults()
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry)
        return registry
    }
}
