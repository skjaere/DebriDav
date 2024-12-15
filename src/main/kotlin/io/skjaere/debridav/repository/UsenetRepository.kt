package io.skjaere.debridav.repository

import io.skjaere.debridav.sabnzbd.UsenetDownload
import org.springframework.data.repository.CrudRepository

interface UsenetRepository : CrudRepository<UsenetDownload, Long>
