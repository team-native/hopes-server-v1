package kr.hs.gsm.hopes.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class DiscordQuestionLogConfig {
    @Bean("questionLogTaskExecutor")
    fun questionLogTaskExecutor(): TaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 100
        setThreadNamePrefix("question-log-")
        setWaitForTasksToCompleteOnShutdown(true)
        initialize()
    }
}
