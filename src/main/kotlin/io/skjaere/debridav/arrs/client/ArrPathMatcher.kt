package io.skjaere.debridav.arrs.client

/**
 * Compares a path returned by an arr (Sonarr's `episodeFile.path`, Radarr's
 * `movieFile.path`) to the path debridav would expect that arr to see for a
 * given local file. The caller is responsible for assembling the expected
 * path — typically `${debridav.mount-path}${local file's path}` — so this
 * just normalises whitespace, leading/trailing slashes, and collapses
 * repeated slashes before equality.
 *
 * Why this matters: the same release name can land in debridav from multiple
 * sources (e.g. two NZBs of the same content, or a torrent and an NZB).
 * Sonarr/Radarr's `/parse` endpoint matches by parsed (series, season,
 * episode) — it doesn't care which physical file lives on disk. Without a
 * path check, repairing an unhealthy copy in debridav would also delete an
 * unrelated good copy that the arr happens to be using.
 */
internal object ArrPathMatcher {
    private val MULTIPLE_SLASHES = Regex("/+")

    fun matches(arrPath: String?, expectedPath: String): Boolean {
        if (arrPath.isNullOrBlank()) return false
        return normalize(arrPath) == normalize(expectedPath)
    }

    /**
     * When [arrPath] is [localPath] (a path inside debridav) under some mount prefix, returns that
     * prefix: the value `debridav.mount-path` would need for the paths to match. Null when the arr's
     * file isn't at [localPath] under any prefix.
     */
    fun mountPrefixFor(arrPath: String, localPath: String): String? {
        val arr = normalize(arrPath)
        val local = normalize(localPath)
        if (local == "/" || !arr.endsWith(local)) return null
        return arr.removeSuffix(local).ifEmpty { "/" }
    }

    private fun normalize(p: String): String =
        "/" + p.trim().trim('/').replace(MULTIPLE_SLASHES, "/")
}
