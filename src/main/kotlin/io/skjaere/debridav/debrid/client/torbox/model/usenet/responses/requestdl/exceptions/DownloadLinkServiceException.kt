package io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.exceptions

class DownloadLinkServiceException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
