package io.skjaere.debridav.pgmq

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.vdsirotkin.pgmq.PgmqClient
import com.vdsirotkin.pgmq.config.PgmqConfiguration
import com.vdsirotkin.pgmq.config.PgmqConnectionFactory
import com.vdsirotkin.pgmq.serialization.JacksonPgmqSerializationProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class PgmqInfrastructureConfiguration {

    @Bean
    fun pgmqConfiguration(props: PgmqConfigurationProperties): PgmqConfiguration =
        object : PgmqConfiguration {
            override val defaultVisibilityTimeout: java.time.Duration = props.defaultVisibilityTimeout
        }

    @Bean
    fun pgmqConnectionFactory(dataSource: DataSource): PgmqConnectionFactory = PgmqConnectionFactory {
        dataSource.connection
    }

    @Bean
    fun pgmqObjectMapper(): ObjectMapper =
        ObjectMapper().registerModule(KotlinModule.Builder().build())

    @Bean
    fun pgmqSerializationProvider(pgmqObjectMapper: ObjectMapper): JacksonPgmqSerializationProvider =
        JacksonPgmqSerializationProvider(pgmqObjectMapper)

    @Bean
    fun pgmqClient(
        connectionFactory: PgmqConnectionFactory,
        serializationProvider: JacksonPgmqSerializationProvider,
        configuration: PgmqConfiguration
    ): PgmqClient = PgmqClient(connectionFactory, serializationProvider, configuration)
}
