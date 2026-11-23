package io.skjaere.debridav.config.auth

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.usenet.NntpConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Startup warnings for login and API-key settings that leave users or Sonarr/Radarr unable to
 * authenticate. Logged once the database config overrides are applied, since those can set them.
 */
@Component
class AuthSetupWarnings(
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val nntpConfigurationProperties: NntpConfigurationProperties,
) {
    private val logger = LoggerFactory.getLogger(AuthSetupWarnings::class.java)

    fun warnings(): List<String> = buildList {
        if (!debridavConfigurationProperties.isWebdavAuthEnabled()) {
            add(
                "DEBRIDAV_WEBDAV_USERNAME and DEBRIDAV_WEBDAV_PASSWORD (debridav.webdav-username/-password) are " +
                        "not both set: WebDAV is open to anyone who can reach it, nobody can log in to the UI, and " +
                        "Sonarr/Radarr can't log in to the qBittorrent API. Set both"
            )
        }
        if (debridavConfigurationProperties.sabnzbdApiKey.isBlank() && usesUsenet()) {
            add(
                "DEBRIDAV_SABNZBD_API_KEY (debridav.sabnzbd-api-key) is not set, so the SABnzbd API rejects " +
                        "Sonarr/Radarr with \"API Key Incorrect\". Set it, and enter the same key in their " +
                        "SABnzbd download client"
            )
        }
    }

    fun logWarnings() = warnings().forEach { logger.warn(it) }

    private fun usesUsenet(): Boolean = nntpConfigurationProperties.pools.isNotEmpty() ||
            DebridProvider.EASYNEWS in debridavConfigurationProperties.debridClients
}
