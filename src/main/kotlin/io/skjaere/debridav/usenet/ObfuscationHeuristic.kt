package io.skjaere.debridav.usenet

/**
 * Detects whether a filename looks like a yenc/rar/upload-style obfuscated name (random
 * hash) versus a real release name. Used by the NZB import pipeline to decide whether
 * to surface the original filename on the WebDAV mount or rewrite it to `<releaseName>.<ext>`
 * for *arr-friendly file naming.
 *
 * Port of SABnzbd's `is_probably_obfuscated` (via nzbdav's `ObfuscationUtil`):
 *
 *   https://github.com/sabnzbd/sabnzbd/blob/64034c5636563b66360aa9dfc1a0b624f4db5cc3/sabnzbd/deobfuscate_filenames.py#L105
 *
 * Cheap structural checks only — no character-frequency analysis, no dictionary lookup.
 * Stays in sync with SABnzbd so we don't surprise users who have a working mental model
 * of which uploads get renamed.
 *
 * Strategy:
 *  1. **Definite obfuscated** patterns short-circuit to `true`. These are the shapes that
 *     show up on real obscured Usenet uploads — 32-char MD5-style hex, long hex+dot
 *     sequences, bracketed hashes, the `abc.xyz.*` prefix used by certain reposters.
 *  2. **Definite NOT obfuscated** patterns short-circuit to `false`. Recognisable
 *     release-name shapes: multi-word with separators, year + title, capital-first words.
 *  3. **Default** is `true` (obfuscated) — anything that doesn't look like a release
 *     name gets renamed.
 *
 * Input: a single filename (no leading directory). Callers that have a path should
 * strip the directory first; we don't do it here so it's clear from a glance what the
 * heuristic is examining.
 */
object ObfuscationHeuristic {

    private val HEX_32 = Regex("^[a-f0-9]{32}$")
    private val HEX_DOT_40_PLUS = Regex("^[a-f0-9.]{40,}$")
    private val HEX_30 = Regex("[a-f0-9]{30}")
    private val BRACKET_TAG = Regex("\\[\\w+]")
    private val ABC_XYZ_PREFIX = Regex("^abc\\.xyz")

    @Suppress("ReturnCount", "MagicNumber")
    fun isProbablyObfuscated(filename: String): Boolean {
        val base = stripLastExtension(filename)

        // Definite-obfuscated patterns first — these are real shapes observed on
        // Usenet uploads. Any one match is enough.

        // `b082fa0beaa644d3aa01045d5b8d0b36.mkv` — 32-char MD5.
        if (HEX_32.matches(base)) return true

        // `0675e29e9abfd2.f7d069dab0b853283cc1b069a25f82.6547.mkv` — 40+ hex/dot.
        if (HEX_DOT_40_PLUS.matches(base)) return true

        // `[Group] 5937bc5e32146e [Brrr].mkv` — bracketed tags + embedded hash.
        if (HEX_30.containsMatchIn(base) && BRACKET_TAG.findAll(base).count() >= 2) return true

        // `abc.xyz.a4c567edbcbf27.BLA.mkv` — reposter-specific prefix.
        if (ABC_XYZ_PREFIX.containsMatchIn(base)) return true

        // Now: definite-NOT-obfuscated patterns. These are recognisable release-name
        // shapes; matching any one is sufficient to declare the name clean.
        val digits = base.count { it.isDigit() }
        val upper = base.count { it.isUpperCase() }
        val lower = base.count { it.isLowerCase() }
        // SABnzbd's algorithm treats only ' ', '.', '_' as separators. We also count '-'
        // because real-world release names use hyphens heavily (group tags, quality
        // segments — `Release-Name-2024-Group`). Without this, names like
        // `release-name-2024` fall through to the obfuscated default.
        val separators = base.count { it == ' ' || it == '.' || it == '_' || it == '-' }

        // `Release Name` — at least two uppercase + two lowercase + a separator.
        if (upper >= 2 && lower >= 2 && separators >= 1) return false

        // `multi.word.release.name` — three or more separators is a strong release-name signal.
        if (separators >= 3) return false

        // `Some.Name.2024` — letters + 4-digit year + separator.
        if (upper + lower >= 4 && digits >= 4 && separators >= 1) return false

        // `Title` — capital first, mostly lowercase. Catches single-word titles.
        if (base.isNotEmpty() && base[0].isUpperCase() && lower > 2 && upper.toDouble() / lower <= 0.25) {
            return false
        }

        // Extension to SABnzbd: short all-lowercase single-word names like `testfile`
        // or `intro` are not obfuscated. SABnzbd's pure algorithm misclassifies these
        // because it expects capital-first or separators on real names — but on
        // Usenet you do see lowercase volume labels (`disc1.bin`, `sample.mkv`) and
        // they shouldn't be renamed. Upper bound of 16 keeps us from accidentally
        // accepting 20+ char lowercase hash hex (those are caught earlier or fall
        // through here as obfuscated).
        if (lower >= 2 && upper == 0 && digits == 0 && separators == 0 && base.length <= 16) return false

        // Extension to SABnzbd: letter-dominant names with at least one separator
        // (`release-name-2024`, `rar5-streaming-test`). SABnzbd's positive rules all
        // require either uppercase letters, 3+ separators, or a 4+ digit year — so
        // lowercase release names with hyphens fall through. We add a "letters
        // outweigh digits 2:1, has a separator" rule. The 2:1 ratio rules out
        // hex-with-dot patterns like `5d.f4ce97a04bcd2` where digit density is high.
        if ((upper + lower) >= 4 && separators >= 1 && (upper + lower) > 2 * digits) return false

        // Fallthrough: treat as obfuscated. SABnzbd's default — anything that doesn't
        // pattern-match a known release-name shape is probably an upload hash.
        return true
    }

    private fun stripLastExtension(filename: String): String {
        val withoutDir = filename.substringAfterLast('/')
        val dot = withoutDir.lastIndexOf('.')
        return if (dot <= 0) withoutDir else withoutDir.substring(0, dot)
    }
}
