package io.skjaere.debridav

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.orm.jpa.JpaTransactionManager
import java.io.PrintWriter
import java.util.*
import javax.sql.DataSource

@Configuration
@EnableJpaRepositories(basePackages = ["io.skjaere.debridav.repository"])
class PersistenceConfiguration(
    @Value("\${spring.datasource.url:''}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username:''}") private val username: String,
    @Value("\${spring.datasource.password:''}") private val password: String
) {
    @Bean
    @ConditionalOnExpression("#{'\${spring.datasource.url}' matches 'jdbc:postgresql.*'}")
    fun dataSource(): DataSource {
        val props = Properties()
        props.setProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource")
        props.setProperty("dataSource.url", jdbcUrl)
        props.setProperty("dataSource.user", username)
        props.setProperty("dataSource.password", password)
        props["dataSource.logWriter"] = PrintWriter(System.out)

        val config = HikariConfig(props)
        return HikariDataSource(config)
    }

    @Bean
    fun transactionManager(): JpaTransactionManager = JpaTransactionManager()
}
