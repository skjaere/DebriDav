package io.skjaere.debridav.config.auth

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val jwtService: JwtService,
    private val debridavConfig: DebridavConfigurationProperties
) {
    @Suppress("ReturnCount")
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<Any> {
        val expectedUsername = debridavConfig.webdavUsername
        val expectedPassword = debridavConfig.webdavPassword

        if (expectedUsername.isNullOrBlank() || expectedPassword.isNullOrBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorBody("No credentials configured"))
        }

        if (request.username != expectedUsername || request.password != expectedPassword) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorBody("Invalid credentials"))
        }

        val token = jwtService.generateToken(request.username)
        return ResponseEntity.ok(LoginResponse(token))
    }
}

data class LoginRequest(val username: String, val password: String)
data class LoginResponse(val token: String)
data class ErrorBody(val error: String)
