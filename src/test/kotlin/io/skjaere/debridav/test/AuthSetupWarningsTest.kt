package io.skjaere.debridav.test

import io.skjaere.debridav.config.auth.AuthSetupWarnings
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.usenet.NntpConfigurationProperties
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.Test

class AuthSetupWarningsTest {
    private val debridav = DebridavConfigurationProperties()
    private val warnings = AuthSetupWarnings(debridav, NntpConfigurationProperties())

    @Test
    fun `warns when the WebDAV credentials are not both set`() {
        debridav.webdavUsername = "admin"
        debridav.webdavPassword = ""

        assertThat(
            warnings.warnings(),
            contains(containsString("DEBRIDAV_WEBDAV_USERNAME and DEBRIDAV_WEBDAV_PASSWORD"))
        )
    }

    @Test
    fun `warns about a missing SABnzbd API key when Usenet is in use`() {
        debridav.webdavUsername = "admin"
        debridav.webdavPassword = "secret"
        debridav.debridClients = listOf(DebridProvider.EASYNEWS)

        assertThat(warnings.warnings(), contains(containsString("DEBRIDAV_SABNZBD_API_KEY")))
    }

    @Test
    fun `does not warn about the SABnzbd API key without Usenet`() {
        debridav.webdavUsername = "admin"
        debridav.webdavPassword = "secret"
        debridav.debridClients = listOf(DebridProvider.REAL_DEBRID)

        assertThat(warnings.warnings(), empty())
    }

    @Test
    fun `does not warn when credentials and the SABnzbd API key are set`() {
        debridav.webdavUsername = "admin"
        debridav.webdavPassword = "secret"
        debridav.sabnzbdApiKey = "key"
        debridav.debridClients = listOf(DebridProvider.EASYNEWS)

        assertThat(warnings.warnings(), empty())
    }
}
