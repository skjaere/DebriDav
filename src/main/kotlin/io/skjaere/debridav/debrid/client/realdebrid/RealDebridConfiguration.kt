package io.skjaere.debridav.debrid.client.realdebrid

import io.skjaere.debridav.ratelimiter.TimeWindowRateLimiter
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
    fun realDebridRateLimiter() = TimeWindowRateLimiter(
        Duration.ofMinutes(WINDOW_DURATION_MINUTES),
        NUMBER_OF_REQUESTS_IN_WINDOW,
        "REAL_DEBRID"
    )
}
