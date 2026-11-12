package kr.hs.gsm.hopes.ai

import com.fasterxml.jackson.databind.JsonNode
import java.util.Base64
import kr.hs.gsm.hopes.api.ApiException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

@Configuration
@ConditionalOnProperty(name = ["hopes.ai.provider"], havingValue = "openai", matchIfMissing = true)
class OpenAiHttpConfiguration {
    @Bean("openAiRestClient")
    fun restClient(
        builder: RestClient.Builder,
        @Value("\${hopes.ai.openai.connect-timeout-ms:5000}") connectTimeout: Int,
        @Value("\${hopes.ai.openai.read-timeout-ms:60000}") readTimeout: Int,
    ): RestClient {
        require(connectTimeout > 0 && readTimeout > 0)
        return builder
            .baseUrl("https://api.openai.com/v1")
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(connectTimeout)
                    setReadTimeout(readTimeout)
                }
            )
            .build()
    }
}

/** Raw Responses API adapter. No SDK convenience output_text field is assumed. */
@Service
@ConditionalOnProperty(name = ["hopes.ai.provider"], havingValue = "openai", matchIfMissing = true)
class OpenAiClient(
    @Value("\${hopes.ai.openai.api-key:}") private val apiKey: String,
    @Value("\${hopes.ai.openai.chat-model:}") private val chatModel: String,
    @Value("\${hopes.ai.max-output-tokens:640}") private val maxOutputTokens: Int,
    @Qualifier("openAiRestClient") private val rest: RestClient,
    @Value("\${hopes.ai.openai.classification-model:}")
    private val classificationModel: String = "",
    @Value("\${hopes.ai.openai.classification-max-output-tokens:256}")
    private val classificationTokens: Int = 256,
) : AiClient {
    init {
        require(maxOutputTokens >= 16 && classificationTokens >= 16)
    }

    override val hasKey: Boolean
        get() = apiKey.isNotBlank() && chatModel.isNotBlank()

    override fun generate(systemPrompt: String, turns: List<Pair<String, String>>) =
        request(systemPrompt, turns, emptyList(), chatModel, maxOutputTokens)

    override fun generateWithAttachments(
        systemPrompt: String,
        turns: List<Pair<String, String>>,
        attachments: List<AiAttachment>,
    ) = request(systemPrompt, turns, attachments, chatModel, maxOutputTokens)

    override fun classifyMealLookup(turns: List<Pair<String, String>>): Boolean {
        val text =
            request(
                    MEAL_INTENT_PROMPT,
                    turns,
                    emptyList(),
                    classificationModel.ifBlank { chatModel },
                    classificationTokens,
                )
                .trim()
                .uppercase()
        return when (text) {
            "MEAL" -> true
            "OTHER" -> false
            else -> throw ApiException(HttpStatus.BAD_GATEWAY, "급식 질문 분류에 실패했습니다. 다시 시도해주세요")
        }
    }

    private fun request(
        prompt: String,
        turns: List<Pair<String, String>>,
        attachments: List<AiAttachment>,
        model: String,
        outputTokens: Int,
    ): String {
        if (!hasKey)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OpenAI 키와 모델이 아직 설정되지 않았습니다")
        require(turns.isNotEmpty() && turns.last().first == "user") {
            "A request must end with a user turn"
        }
        val input =
            turns.mapIndexed { index, (role, text) ->
                require(role in setOf("user", "model", "assistant"))
                val normalizedRole = if (role == "model") "assistant" else role
                if (index != turns.lastIndex) mapOf("role" to normalizedRole, "content" to text)
                else {
                    val content =
                        mutableListOf<Map<String, Any>>(
                            mapOf("type" to "input_text", "text" to text)
                        )
                    attachments.forEachIndexed { attachmentIndex, attachment ->
                        content += mapOf("type" to "input_text", "text" to attachment.label)
                        val data =
                            "data:${attachment.mimeType};base64," +
                                Base64.getEncoder().encodeToString(attachment.bytes)
                        content +=
                            when (attachment.mimeType) {
                                "image/png",
                                "image/jpeg" ->
                                    mapOf(
                                        "type" to "input_image",
                                        "image_url" to data,
                                        "detail" to "auto",
                                    )
                                "application/pdf" ->
                                    mapOf(
                                        "type" to "input_file",
                                        "filename" to "attachment-${attachmentIndex + 1}.pdf",
                                        "file_data" to data,
                                    )
                                else ->
                                    throw ApiException(
                                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                        "지원하지 않는 AI 첨부 형식입니다",
                                    )
                            }
                    }
                    mapOf("role" to normalizedRole, "content" to content)
                }
            }
        val response =
            try {
                rest
                    .post()
                    .uri("/responses")
                    .header("Authorization", "Bearer $apiKey")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        mapOf(
                            "model" to model,
                            "instructions" to prompt,
                            "input" to input,
                            "max_output_tokens" to outputTokens,
                            "store" to false,
                        )
                    )
                    .retrieve()
                    .onStatus({ it.isError }) { _, upstream ->
                        // Never attach the upstream body/exception: it may contain user data or
                        // credentials.
                        if (upstream.statusCode.value() == 429)
                            throw ApiException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                "AI 요청 한도에 도달했습니다. 잠시 후 다시 시도해주세요",
                            )
                        throw ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "AI 응답 생성에 실패했습니다. 잠시 후 다시 시도해주세요",
                        )
                    }
                    .body(JsonNode::class.java)
            } catch (e: ApiException) {
                throw e
            } catch (e: RestClientException) {
                throw ApiException(HttpStatus.BAD_GATEWAY, "AI 연결에 실패했습니다. 잠시 후 다시 시도해주세요")
            }

        if (
            response == null ||
                response.path("status").asText() != "completed" ||
                !response.path("output").isArray
        )
            throw ApiException(HttpStatus.BAD_GATEWAY, "AI 답변 생성이 완료되지 않았습니다. 다시 시도해주세요")
        val parts =
            response
                .path("output")
                .filter { it.path("type").asText() == "message" }
                .flatMap { it.path("content").toList() }
        if (parts.any { it.path("type").asText() == "refusal" })
            throw ApiException(HttpStatus.BAD_GATEWAY, "이 요청에 대한 AI 답변을 생성할 수 없습니다")
        val answer =
            parts
                .filter { it.path("type").asText() == "output_text" && it.path("text").isTextual }
                .joinToString("\n") { it.path("text").asText() }
        if (answer.isBlank()) throw ApiException(HttpStatus.BAD_GATEWAY, "AI가 빈 답변을 반환했습니다")
        return answer
    }
}
