package io.skjaere.debridav

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer

@SpringBootApplication
class DebriDavApplication : SpringBootServletInitializer()

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<DebriDavApplication>(*args)
}
