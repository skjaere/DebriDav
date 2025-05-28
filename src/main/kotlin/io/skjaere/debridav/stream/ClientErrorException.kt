package io.skjaere.debridav.stream

class ClientErrorException(val statusCode: Int) : RuntimeException()

