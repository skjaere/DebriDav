package io.skjaere.debridav.test.integrationtest.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

// Test-only filter chain that bypasses auth on /api/** and /actuator/**. Imported by
// integration tests whose subject is the business logic of the endpoint, not its auth.
// Tests that exercise auth itself (e.g. JwtAuthIT) deliberately do NOT @Import this and
// run against the production SecurityConfiguration instead.
//
// Higher precedence than the production filter chain so this one matches first.
@TestConfiguration
class TestSecurityConfiguration {
    // Single chain matching everything; @Order(0) puts it ahead of the production chains
    // so the FilterChainProxy hits this one first. A broader matcher than the production
    // chain avoids Spring Security 7's UnreachableFilterChainException, which fires when
    // two chains share the same matcher.
    @Bean
    @Primary
    @Order(0)
    fun testPermitAllSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/**")
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }
}
