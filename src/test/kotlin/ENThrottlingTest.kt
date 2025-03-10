/*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpHeaders.Authorization
import io.ktor.serialization.kotlinx.json.json
import io.skjaere.debridav.RateLimitingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*

class ENThrottlingTest {
    private val logger = LoggerFactory.getLogger(ENThrottlingTest::class.java)
    val url =
        "https://members.easynews.com/dl/auto/443/b7985efff7916955213ad25c9a939cd209ed8370e4715.mkv/
        Hells.Kitchen.US.S21E14.Lights.Camera.Sabotage.1080p.NF.WEB-DL.DDP5.1.H.264-NTb.mkv
        ?sid=9e90e679797ceb291a5cf561b523a4054b3c978a:0
        &sig=eNoBYACf_66YK9WEgUZr1FHWqZ8TjUbJpcPsVk9Y1H6R_BiIpF3Ffh7fKhmMQuTa4rTvvz_x
        Hruwcop6ajUBjf86sTh5rXYLt1ScsCU3X22V3TfC4VBHrFVFe0_rY1A7HIYbFLPw5pe5MN0"
    val auth = "uxlrvsntac:jzel-fukb-brps".let {
        "Basic ${Base64.getEncoder().encodeToString(it.toByteArray())}"
    }
    val throttlingService = RateLimitingService()

    fun httpClient(): HttpClient {
        val lock = Mutex()

        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 2000
                requestTimeoutMillis = 2000
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                    })
            }

        }
        */
/*client.plugin(HttpSend).intercept { request ->
            if (lock.isLocked) {
                do {
                    delay(500L)
                } while (lock.isLocked)
            }
            logger.info("executing request: ${request.url}")
            val originalCall = execute(request)
            logger.info("got ${originalCall.response.status}")
            if (originalCall.response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                logger.info("originalCall: $originalCall")
            }
            if (originalCall.response.status == HttpStatusCode.BadRequest) {
                var result = originalCall
                var attempts = 1
                lock.withLock {
                    do {
                        val waitMs = 1000L * attempts
                        logger.info("waiting $waitMs ms")
                        delay(waitMs)
                        request.url(url)
                        request.headers {
                            append(Authorization, auth)
                            //append(HttpHeaders.Range, "bytes=0-100")
                        }
                        logger.info("url: ${request.url}")
                        result = execute(request)
                        logger.info("got ${result.response.status}")
                        logger.info("originalCall: $originalCall")
                        attempts++
                    } while (result.response.status == HttpStatusCode.BadRequest && attempts <= 5)
                }
                result
            } else originalCall
        }*//*

        return client
    }

    @Test
    fun thatThrottlingWorks1() {
        val client = httpClient()


        runBlocking {
            repeat(100) {
                val result = throttlingService.doWithRateLimit("test", Duration.ofMillis(20000), 5) {
                    client.get(url) {
                        headers {
                            append(Authorization, auth)
                            append(HttpHeaders.Range, "bytes=1-101")
                        }
                        timeout {
                            requestTimeoutMillis = 2000
                        }
                        accept(ContentType.Any)
                    }
                }
                logger.info("${result.status}")
            }
        }
    }

    @Test
    fun thatThrottlingWorks() {

        runBlocking {
            repeat(100) {
                delay(900)
                throttlingService.doWithRateLimit("test", Duration.ofSeconds(10), 5) {
                    logger.info("doing it")
                }
            }
        }
    }
}*/
