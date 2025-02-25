package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.DebridFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.ProviderError

const val MAGNET = "magnet:?xt=urn:btih:hash&dn=test&tr="
val premiumizeCachedFile = CachedFile(
    path = "/foo/bar.mkv",
    provider = DebridProvider.PREMIUMIZE,
    size = 100L,
    link = "http://test.test/bar.mkv",
    lastChecked = 100,
    params = mapOf(),
    mimeType = "video/mkv"
)
val realDebridCachedFile = CachedFile(
    path = "/foo/bar.mkv",
    provider = DebridProvider.REAL_DEBRID,
    size = 100L,
    link = "http://test.test/bar.mkv",
    lastChecked = 100,
    params = mapOf(),
    mimeType = "video/mkv"
)
val easynewsCachedFile = CachedFile(
    path = "/foo/bar.mkv",
    provider = DebridProvider.EASYNEWS,
    size = 100L,
    link = "http://test.test/bar.mkv",
    lastChecked = 100,
    params = mapOf(),
    mimeType = "video/mkv"
)
val debridFileContents = DebridCachedTorrentContent(
    originalPath = "/foo/bar.mkv",
    size = 100L,
    modified = 1730477942L,
    magnet = MAGNET,
    debridLinks = mutableListOf(realDebridCachedFile, premiumizeCachedFile),
    mimeType = "video/mp4"
)

val usenetDebridFileContents = DebridCachedUsenetReleaseContent(
    originalPath = "/foo/bar.mkv",
    size = 100L,
    modified = 1730477942L,
    releaseName = "test.release",
    debridLinks = mutableListOf(easynewsCachedFile),
    mimeType = "video/mp4"
)

fun DebridFileContents.deepCopy(): DebridFileContents {
    val copy = when (this) {
        is DebridCachedTorrentContent -> DebridCachedTorrentContent(this.magnet!!)
        is DebridCachedUsenetReleaseContent -> DebridCachedUsenetReleaseContent(this.releaseName!!)
        else -> error("Unexpected type: ${this::class}")
    }
    copy.debridLinks = this.debridLinks!!.map { link ->
        link.copy()
    } as MutableList<DebridFile>
    copy.mimeType = this.mimeType
    copy.size = this.size
    copy.originalPath = this.originalPath
    copy.modified = this.modified
    copy.id = this.id

    return copy
}

private fun DebridFile.copy(): DebridFile =
    when (this) {
        is CachedFile -> CachedFile(
            this.path!!,
            this.size!!,
            this.mimeType!!,
            this.link!!,
            this.params!!.entries.associate { it.key to it.value },
            this.lastChecked!!,
            this.provider!!
        )

        is MissingFile -> MissingFile(this.provider!!, this.lastChecked!!)
        is ProviderError -> ProviderError(this.provider!!, this.lastChecked!!)
        else -> error("Unexpected type: ${this::class}")
    }
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
