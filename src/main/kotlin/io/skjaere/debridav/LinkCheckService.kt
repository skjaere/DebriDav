package io.skjaere.debridav

import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.headers


import io.ktor.http.isSuccess
import org.springframework.stereotype.Service
import java.io.IOException

@Service
class LinkCheckService(
    private val httpClient: HttpClient
) {

    @Suppress("SwallowedException")
    suspend fun isLinkAlive(link: String, cookies: Map<String, String>): Boolean {
        return try {
            httpClient.head(link) {
                headers {
                    if (cookies.isNotEmpty()) {
                        append(
                            "Cookie", cookies.entries
                                .joinToString(";") { "${it.key}=${it.value}" }
                        )
                    }
                }
            }.status.isSuccess()
        } catch (e: IOException) {
            false
        }
    }
}
