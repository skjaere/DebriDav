package io.skjaere.debridav.fs

import java.io.InputStream

@Suppress("TooManyFunctions")
interface FileService {
    fun createDebridFile(
        path: String,
        debridFileContents: DebridFileContents
    ): DebridFsFile

    fun getDebridFileContents(path: String): DebridFileContents?

    fun writeContentsToFile(path: String, debridFileContents: DebridFileContents)

    fun moveResource(itemPath: String, destination: String, name: String)

    fun deleteFile(path: String)

    fun handleNoLongerCachedFile(path: String)

    fun createLocalFile(
        path: String,
        inputStream: InputStream
    ): DebridFsLocalFile

    fun getFileAtPath(path: String): DebridFsItem?

    fun createDirectory(path: String): DebridFsDirectory

    fun getChildren(path: String): List<DebridFsItem>

    fun deleteFilesWithHash(hash: String)
}
