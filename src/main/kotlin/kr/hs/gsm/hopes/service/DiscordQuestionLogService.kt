package kr.hs.gsm.hopes.service

import kr.hs.gsm.hopes.domain.ClientPlatform
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.TaskExecutor
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Instant

data class DiscordQuestionLogEvent(
    val conversationId: Long,
    val clientPlatform: ClientPlatform,
    val question: String,
    val receivedAt: Instant,
)

@Service
class DiscordQuestionLogService(
    @Value("\${hopes.discord.question-log-webhook-url:}") private val webhookUrl: String,
    restClientBuilder: RestClient.Builder,
    @Qualifier("questionLogTaskExecutor") private val taskExecutor: TaskExecutor,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val restClient = restClientBuilder.build()

    fun publish(event: DiscordQuestionLogEvent) {
        if (webhookUrl.isBlank()) return
        try {
            taskExecutor.execute {
                try {
                    val platform = when (event.clientPlatform) {
                        ClientPlatform.WEB -> "웹"
                        ClientPlatform.APP -> "앱"
                        ClientPlatform.UNKNOWN -> "알 수 없음"
                    }
                    restClient.post()
                        .uri(webhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                            mapOf(
                                "username" to "Hopes 질문 로그",
                                "allowed_mentions" to mapOf("parse" to emptyList<String>()),
                                "embeds" to listOf(
                                    mapOf(
                                        "title" to "새 질문 · $platform",
                                        "description" to event.question.take(DISCORD_DESCRIPTION_LIMIT),
                                        "color" to if (event.clientPlatform == ClientPlatform.APP) 0x8B5CF6 else 0x0EA5E9,
                                        "fields" to listOf(
                                            mapOf("name" to "출처", "value" to platform, "inline" to true),
                                            mapOf("name" to "대화 ID", "value" to event.conversationId.toString(), "inline" to true),
                                        ),
                                        "timestamp" to event.receivedAt.toString(),
                                    )
                                ),
                            )
                        )
                        .retrieve()
                        .toBodilessEntity()
                } catch (exception: Exception) {
                    // 웹훅 URL에는 인증 토큰이 포함되므로 예외 메시지나 URL은 로그에 남기지 않는다.
                    logger.warn("Discord 질문 로그 전송 실패 ({})", exception.javaClass.simpleName)
                }
            }
        } catch (exception: Exception) {
            logger.warn("Discord 질문 로그 작업 등록 실패 ({})", exception.javaClass.simpleName)
        }
    }

    companion object {
        private const val DISCORD_DESCRIPTION_LIMIT = 4_000
    }
}
