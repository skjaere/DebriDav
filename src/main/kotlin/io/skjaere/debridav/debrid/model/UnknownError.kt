package io.skjaere.debridav.debrid.model

import io.skjaere.debridav.fs.DebridProvider

class UnknownError(override val provider: DebridProvider, override val lastChecked: Long) : DebridFile
