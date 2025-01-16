package io.skjaere.debridav.test

import io.skjaere.debridav.fs.DebridFileContents
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MigrationTest {

    @Test
    fun `that old version can be deserialized`() {
        val oldJson = """
            {
              "originalPath": "/foo/bar.mkv",
              "size": 100,
              "modified": 1730477942,
              "magnet": "magnet:?xt=urn:btih:hash&dn=test&tr=",
              "debridLinks": [
                {
                  "type": "io.skjaere.debridav.debrid.model.CachedFile",
                  "path": "/foo/bar.mkv",
                  "size": 100,
                  "mimeType": "video/mkv",
                  "link": "http://test.test/bar.mkv",
                  "provider": "REAL_DEBRID",
                  "lastChecked": 100
                },
                {
                  "type": "io.skjaere.debridav.debrid.model.CachedFile",
                  "path": "/foo/bar.mkv",
                  "size": 100,
                  "mimeType": "video/mkv",
                  "link": "http://test.test/bar.mkv",
                  "provider": "PREMIUMIZE",
                  "lastChecked": 100
                }
              ]
            }
        """.trimIndent()
        val oldDebridFileContentsDeserialized = Json.decodeFromString<DebridFileContents>(oldJson)
        assertEquals(oldDebridFileContentsDeserialized.type, DebridFileContents.Type.TORRENT_MAGNET)
    }

    @Test
    fun `that new version can be deserialized`() {
        val newJson = """
            {
              "originalPath": "/foo/bar.mkv",
              "size": 100,
              "modified": 1730477942,
              "magnet": "Release.Name.2024.1080p.GrP",
              "type": "USENET_RELEASE",
              "debridLinks": [
                {
                  "type": "io.skjaere.debridav.debrid.model.CachedFile",
                  "path": "/foo/bar.mkv",
                  "size": 100,
                  "mimeType": "video/mkv",
                  "link": "http://test.test/bar.mkv",
                  "provider": "REAL_DEBRID",
                  "lastChecked": 100
                },
                {
                  "type": "io.skjaere.debridav.debrid.model.CachedFile",
                  "path": "/foo/bar.mkv",
                  "size": 100,
                  "mimeType": "video/mkv",
                  "link": "http://test.test/bar.mkv",
                  "provider": "PREMIUMIZE",
                  "lastChecked": 100
                }
              ]
            }
        """.trimIndent()
        val oldDebridFileContentsDeserialized = Json.decodeFromString<DebridFileContents>(newJson)
        assertEquals(oldDebridFileContentsDeserialized.type, DebridFileContents.Type.USENET_RELEASE)
    }
}
