package io.skjaere.debridav.fs

import io.skjaere.debridav.debrid.model.MissingFile

sealed interface DebridFsItem {
    val id: Long?
    val path: String
}

data class DebridFsFile(
    override val id: Long?,
    val name: String,
    val size: Long,
    val lastModified: Long,
    override val path: String,
    val contents: DebridFileContents
) : DebridFsItem {
    fun isNoLongerCached(debridClients: List<DebridProvider>) =
        contents
            .debridLinks
            .filter { debridClients.contains(it.provider) }
            .all { it is MissingFile }
}

data class DebridFsDirectory(
    override val id: Long?,
    val name: String,
    override val path: String,
    val lastModified: Long,
    val children: List<DebridFsItem>
) : DebridFsItem {
    fun getPathString(): String {
        return if (path == "/") "" else path
    }
}

data class DebridFsLocalFile(
    override val id: Long?,
    val name: String,
    override val path: String,
    val size: Long,
    val lastModified: Long,
    val contents: ByteArray,
    val mimeType: String?,
) : DebridFsItem {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DebridFsLocalFile

        if (lastModified != other.lastModified) return false
        if (name != other.name) return false
        if (path != other.path) return false

        return true
    }

    override fun hashCode(): Int {
        var result = lastModified.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + path.hashCode()
        return result
    }
}
