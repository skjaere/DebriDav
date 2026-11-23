package io.skjaere.debridav.arrs

import io.skjaere.debridav.arrs.client.ArrPathMatcher
import io.skjaere.debridav.arrs.client.DeleteAndSearchResult

/**
 * Why a repair left an unhealthy file in debridav instead of deleting it, for a WARN log. [result]
 * is what the arrs answered for [name]; [expectedPaths] are the paths debridav expected them to
 * report, built from [mountPath].
 */
fun keptFileWarning(
    name: String,
    result: DeleteAndSearchResult,
    expectedPaths: Set<String>,
    mountPath: String,
): String = when (result) {
    is DeleteAndSearchResult.ImportedElsewhere -> {
        val localPaths = expectedPaths.map { it.removePrefix(mountPath) }
        val prefix = result.arrPaths.firstNotNullOfOrNull { arrPath ->
            localPaths.firstNotNullOfOrNull { ArrPathMatcher.mountPrefixFor(arrPath, it) }
        }
        if (prefix != null) {
            "Not deleting '$name': the arr has it imported at ${result.arrPaths}, which is this file under " +
                    "mount path '$prefix', but debridav.mount-path is '$mountPath'. Set debridav.mount-path " +
                    "to '$prefix' so repairs can replace the file in the arr"
        } else {
            val arrPaths = result.arrPaths.ifEmpty { listOf("(path could not be read)") }
            "Not deleting '$name': the arr has it imported at $arrPaths, not at $expectedPaths. If that is " +
                    "this file under a different mount, fix debridav.mount-path; if it is another copy, delete " +
                    "debridav's copy by hand"
        }
    }

    is DeleteAndSearchResult.Failed ->
        "Not deleting '$name': an arr could not be asked whether it uses the file (${result.reason}). " +
                "The file is checked again next interval"

    DeleteAndSearchResult.Replaced, DeleteAndSearchResult.NotImported ->
        "'$name' was not kept: $result"
}
