package io.william.debridav

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer
import java.util.concurrent.Executors

@SpringBootApplication
class DebridApplication : SpringBootServletInitializer()

fun main(args: Array<String>) {
    runApplication<DebridApplication>(*args)
}

val Dispatchers.LOOM: CoroutineDispatcher
    get() = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

