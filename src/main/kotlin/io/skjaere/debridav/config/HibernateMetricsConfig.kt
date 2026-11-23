package io.skjaere.debridav.config

import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.hibernate.stat.HibernateMetrics
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Manual registration of Hibernate's Micrometer binder. Spring Boot 4 dropped
 * the `HibernateMetricsAutoConfiguration` that previously wired this for us;
 * without explicit binding, `spring.jpa.properties.hibernate.generate_statistics=true`
 * generates the statistics inside Hibernate but nothing reads them into
 * Micrometer, so `/actuator/prometheus` shows no `hibernate_*` series.
 *
 * Uses [org.hibernate.stat.HibernateMetrics] from the `hibernate-micrometer`
 * artifact (Hibernate ORM team's own integration), not the deprecated
 * [io.micrometer.core.instrument.binder.jpa.HibernateMetrics] which still
 * imports `javax.persistence.*` and crashes at runtime against Hibernate 7
 * (jakarta.persistence). They share the same class name and constructor
 * signature but the Hibernate-shipped one is Jakarta EE compatible.
 *
 * Exposes the standard set: query executions, max/avg query time, sessions
 * opened/closed, transactions committed/rolled back, cache hits/misses.
 * The `name` arg becomes the `entityManagerFactory` tag on the metrics.
 */
@Configuration
class HibernateMetricsConfig {
    @Bean
    fun hibernateMetrics(
        entityManagerFactory: EntityManagerFactory,
        meterRegistry: MeterRegistry,
    ): HibernateMetrics {
        val sessionFactory = entityManagerFactory.unwrap(SessionFactory::class.java)
        return HibernateMetrics(sessionFactory, "debridav", emptyList()).also {
            it.bindTo(meterRegistry)
        }
    }
}
