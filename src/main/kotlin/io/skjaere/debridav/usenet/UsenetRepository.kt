package io.skjaere.debridav.usenet

import jakarta.transaction.Transactional
import org.springframework.data.repository.CrudRepository

@Transactional
interface UsenetRepository : CrudRepository<UsenetDownload, Long>
