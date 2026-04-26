package io.skjaere.debridav.config

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [ConfigOverrideController::class])
class ConfigApiExceptionHandler {

    @ExceptionHandler(KeyNotWhitelistedException::class)
    fun handleNotWhitelisted(ex: KeyNotWhitelistedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(ex.message ?: "Key not whitelisted"))

    @ExceptionHandler(OverrideNotFoundException::class)
    fun handleNotFound(ex: OverrideNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(ex.message ?: "Override not found"))
}

data class ErrorResponse(val error: String)
