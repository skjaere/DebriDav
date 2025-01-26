package io.skjaere.debridav.repository

import io.skjaere.debridav.usenet.UsenetDownload
import jakarta.transaction.Transactional
import org.springframework.data.repository.CrudRepository

@Transactional
interface UsenetRepository : CrudRepository<UsenetDownload, Long>
