package io.skjaere.debridav.config.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.StrictHttpFirewall

@Configuration
@EnableWebSecurity
class SecurityConfiguration(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val authConfig: AuthConfigurationProperties
) {
    @Bean
    @Order(1)
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/**", "/actuator/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { it.authenticationEntryPoint(unauthorizedEntryPoint()) }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                // Auth and stream endpoints are always public
                auth.requestMatchers("/api/v1/auth/**").permitAll()
                auth.requestMatchers("/api/v1/stream/**").permitAll()

                // Config API is protected when auth is enabled
                if (authConfig.enabled) {
                    auth.requestMatchers("/api/v1/config/**").authenticated()
                    auth.requestMatchers("/api/v1/queue/**").authenticated()
                } else {
                    auth.requestMatchers("/api/v1/config/**").permitAll()
                    auth.requestMatchers("/api/v1/queue/**").permitAll()
                }

                // Conditionally protect qBittorrent API
                if (authConfig.protectQbittorrentApi) {
                    auth.requestMatchers("/api/v2/**").authenticated()
                }

                // Conditionally protect SABnzbd API
                if (authConfig.protectSabnzbdApi) {
                    auth.requestMatchers("/api").authenticated()
                }

                // Conditionally protect actuator
                if (authConfig.protectActuator) {
                    auth.requestMatchers("/actuator/**").authenticated()
                }

                // All other API/actuator paths are public
                auth.anyRequest().permitAll()
            }

        return http.build()
    }

    @Bean
    @Order(2)
    fun webDavSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }

        return http.build()
    }

    @Bean
    fun httpFirewall(): HttpFirewall {
        val firewall = StrictHttpFirewall()
        // Allow WebDAV methods (PROPFIND, MKCOL, COPY, MOVE, LOCK, UNLOCK, PROPPATCH)
        firewall.setUnsafeAllowAnyHttpMethod(true)
        return firewall
    }

    private fun unauthorizedEntryPoint() = AuthenticationEntryPoint {
            _: HttpServletRequest, response: HttpServletResponse, _: AuthenticationException ->
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = "application/json"
        response.writer.write("""{"error":"Unauthorized"}""")
    }
}
