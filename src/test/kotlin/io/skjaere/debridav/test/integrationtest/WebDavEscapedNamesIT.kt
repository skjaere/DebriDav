package io.skjaere.debridav.test.integrationtest

import com.github.sardine.SardineFactory
import com.github.sardine.impl.SardineException
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import kotlin.test.fail
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

/**
 * Names with characters clients have to escape. Spring Security's default firewall rejected them,
 * so such a file was listed but returned 400 on every GET, PROPFIND and MOVE.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class WebDavEscapedNamesIT {

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "100% Wolf (2020)|100%25%20Wolf%20(2020)",
            // rclone escapes ; as %3B, other clients send it raw.
            "encoded semicolon|semi%3Bcolon",
            "raw semicolon|semi;colon",
        ]
    )
    fun `a directory whose name needs escaping can be listed, opened, moved and deleted`(
        case: String,
        encodedName: String,
    ) {
        val base = "http://localhost:$randomServerPort/webdav"
        deleteIfExists("$base/$encodedName")
        deleteIfExists("$base/$encodedName-moved")
        try {
            sardine.createDirectory("$base/$encodedName")
            val decodedName = java.net.URI("/$encodedName").path.substring(1)
            assertThat("$case listed in the root", sardine.list("$base/").map { it.name }, hasItem(decodedName))
            sardine.list("$base/$encodedName")
            sardine.move("$base/$encodedName", "$base/$encodedName-moved")
            sardine.list("$base/$encodedName-moved")
            sardine.delete("$base/$encodedName-moved")
        } catch (e: SardineException) {
            fail("$case: HTTP ${e.statusCode} ${e.responsePhrase}")
        } finally {
            deleteIfExists("$base/$encodedName")
            deleteIfExists("$base/$encodedName-moved")
        }
    }

    private fun deleteIfExists(url: String) {
        runCatching { sardine.delete(url) }
    }
}
