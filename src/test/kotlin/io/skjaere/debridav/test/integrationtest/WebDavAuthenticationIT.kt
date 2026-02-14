package io.skjaere.debridav.test.integrationtest

import com.github.sardine.SardineFactory
import com.github.sardine.impl.SardineException
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import kotlin.test.assertFailsWith
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debridav.debrid-clients=premiumize",
        "debridav.webdav-username=testuser",
        "debridav.webdav-password=testpass"
    ]
)
@MockServerTest
class WebDavAuthenticationIT {

    @LocalServerPort
    var randomServerPort: Int = 0

    @Test
    fun `that unauthenticated request is rejected`() {
        val sardine = SardineFactory.begin()
        assertFailsWith<SardineException> {
            sardine.list("http://localhost:${randomServerPort}/")
        }
    }

    @Test
    fun `that wrong credentials are rejected`() {
        val sardine = SardineFactory.begin("wronguser", "wrongpass")
        assertFailsWith<SardineException> {
            sardine.list("http://localhost:${randomServerPort}/")
        }
    }

    @Test
    fun `that correct credentials succeed`() {
        val sardine = SardineFactory.begin("testuser", "testpass")
        val resources = sardine.list("http://localhost:${randomServerPort}/")
        assertThat(resources.isEmpty(), `is`(false))
    }
}
