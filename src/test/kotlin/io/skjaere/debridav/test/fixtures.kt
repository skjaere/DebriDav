/*
package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.fs.DebridCachedContentFileContents

const val MAGNET = "magnet:?xt=urn:btih:hash&dn=test&tr="
val premiumizeCachedFile = CachedFile(
    path = "/foo/bar.mkv",
    provider = DebridProvider.PREMIUMIZE,
    size = 100L,
    link = "http://test.test/premiumize/bar.mkv",
    lastChecked = 100,
    params = mapOf(),
    mimeType = "video/mkv"
)
val realDebridCachedFile = CachedFile(
    path = "/foo/bar.mkv",
    provider = DebridProvider.REAL_DEBRID,
    size = 100L,
    link = "http://test.test/real_debrid/bar.mkv",
    lastChecked = 100,
    params = mapOf(),
    mimeType = "video/mkv"
)
val debridFileContents = DebridCachedContentFileContents(
    originalPath = "/foo/bar.mkv",
    size = 100L,
    modified = 1730477942L,
    key = MAGNET,
    debridLinks = mutableListOf(realDebridCachedFile, premiumizeCachedFile)
)
*/
/*

val debridFileContents = DebridFileContents(
        "a/b/c/movie.mkv",
        100,
        1000,
        magnet,
        mutableListOf(
                DebridLink(
                        DebridProvider.PREMIUMIZE,
                        "http://localhost:999/deadLink",
                )
        )
)

val directDownloadResponse = listOf(CachedFile(
        "a/b/c/movie.mkv",
        1000,
        "video/mp4",
        "https://test.com/video.mkv",
))*/

