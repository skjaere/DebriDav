package io.skjaere.debridav.repository

import io.skjaere.debridav.fs.Blob
import org.springframework.data.repository.CrudRepository

interface BlobRepository : CrudRepository<Blob, Long>
