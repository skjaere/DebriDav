package io.skjaere.debridav.config

import io.ktor.network.selector.SelectorManager
import io.skjaere.nntp.NntpAuthenticationException
import io.skjaere.nntp.NntpClient
import io.skjaere.nntp.NntpException
import kotlinx.coroutines.Dispatchers
import org.springframework.stereotype.Service

@Service
class NntpPoolTester {
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun test(pool: NntpPoolDto): TestResult {
        val selectorManager = SelectorManager(Dispatchers.IO)
        try {
            val client = NntpClient.connect(
                host = pool.host,
                port = pool.port,
                selectorManager = selectorManager,
                useTls = pool.useTls,
                username = pool.username,
                password = pool.password
            )
            client.use { it.quit() }
            return TestResult(success = true, message = "Connected successfully")
        } catch (e: NntpAuthenticationException) {
            return TestResult(success = false, message = "Authentication failed: ${e.message}")
        } catch (e: NntpException) {
            return TestResult(success = false, message = e.message ?: "NNTP error")
        } catch (e: Exception) {
            return TestResult(success = false, message = e.message ?: "Connection failed")
        } finally {
            selectorManager.close()
        }
    }
}
