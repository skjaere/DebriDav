package io.skjaere.debridav.debrid.client

import io.ktor.client.statement.HttpResponse
import io.skjaere.debridav.fs.DebridProvider

interface DebridClient {
    fun getProvider(): DebridProvider
    fun getMsToWaitFrom429Response(httpResponse: HttpResponse): Long
}
