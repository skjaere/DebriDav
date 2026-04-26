package io.skjaere.debridav

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class DebriDavApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<DebriDavApplication>(*args)
}
