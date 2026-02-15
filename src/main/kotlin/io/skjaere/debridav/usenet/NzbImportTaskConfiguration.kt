package io.skjaere.debridav.usenet

import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer
import com.github.kagkarlsson.scheduler.serializer.Serializer
import com.github.kagkarlsson.scheduler.task.TaskDescriptor
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Optional

@Configuration
@ConditionalOnProperty("nntp.enabled", havingValue = "true")
class NzbImportTaskConfiguration {

    companion object {
        val NZB_IMPORT_DESCRIPTOR: TaskDescriptor<NzbImportTaskData> =
            TaskDescriptor.of("nzb-import", NzbImportTaskData::class.java)
    }

    @Bean
    fun nzbImportTask(nzbImportService: NzbImportService): OneTimeTask<NzbImportTaskData> =
        Tasks.oneTime(NZB_IMPORT_DESCRIPTOR)
            .execute { taskInstance, _ ->
                nzbImportService.executeImport(taskInstance.data)
            }

    @Bean
    fun dbSchedulerCustomizer(): DbSchedulerCustomizer = object : DbSchedulerCustomizer {
        override fun serializer(): Optional<Serializer> {
            val objectMapper = JacksonSerializer.getDefaultObjectMapper()
            objectMapper.registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
            return Optional.of(JacksonSerializer(objectMapper))
        }
    }
}
