package io.skjaere.debridav.repository

import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import org.springframework.data.repository.CrudRepository

interface NzbDocumentRepository : CrudRepository<NzbDocumentEntity, Long>
