package io.skjaere.debridav.fs

data class FileEntryDto(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val lastModified: Long?,
    val mimeType: String?
)
