package io.skjaere.debridav

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DebriDavApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<DebriDavApplication>(*args)
}
