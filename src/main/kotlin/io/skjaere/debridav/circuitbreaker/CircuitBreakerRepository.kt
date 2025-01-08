package io.skjaere.debridav.circuitbreaker

import org.springframework.data.repository.CrudRepository

interface CircuitBreakerRepository : CrudRepository<CircuitBreaker, Long> {
}