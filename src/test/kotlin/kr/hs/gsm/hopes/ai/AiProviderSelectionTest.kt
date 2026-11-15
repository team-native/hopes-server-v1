package kr.hs.gsm.hopes.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

class AiProviderSelectionTest {
    @Configuration(proxyBeanMethods = false)
    class HttpConfig {
        @Bean fun builder(): RestClient.Builder = RestClient.builder()
    }

    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(
                HttpConfig::class.java,
                OpenAiHttpConfiguration::class.java,
                OpenAiClient::class.java,
                GeminiClient::class.java,
            )
            .withPropertyValues(
                "hopes.ai.gemini-api-key=test-gemini",
                "hopes.ai.chat-model=legacy-model",
            )

    @Test
    fun `only configured OpenAI is selected and incomplete settings fall back`() {
        for (provider in listOf("auto", "openai")) {
            for ((key, model) in listOf("" to "", "key" to "", "" to "model", "key" to "model")) {
                val openAi = key.isNotBlank() && model.isNotBlank()
                runner
                    .withPropertyValues(
                        "hopes.ai.provider=$provider",
                        "hopes.ai.openai.api-key=$key",
                        "hopes.ai.openai.chat-model=$model",
                    )
                    .run { context ->
                        assertThat(context).hasNotFailed().hasSingleBean(AiClient::class.java)
                        assertThat(context.getBean(AiClient::class.java))
                            .isInstanceOf(
                                if (openAi) OpenAiClient::class.java else GeminiClient::class.java
                            )
                        assertThat(context.containsBean("openAiRestClient")).isEqualTo(openAi)
                        assertThat(context.getBean(AiClient::class.java).hasKey).isTrue()
                    }
            }
        }
    }

    @Test
    fun `explicit Gemini overrides configured OpenAI`() {
        runner
            .withPropertyValues(
                "hopes.ai.provider=gemini",
                "hopes.ai.openai.api-key=key",
                "hopes.ai.openai.chat-model=model",
            )
            .run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(AiClient::class.java)
                assertThat(context.getBean(AiClient::class.java))
                    .isInstanceOf(GeminiClient::class.java)
            }
    }

    @Test
    fun `default configuration without either key remains inactive`() {
        runner
            .withPropertyValues(
                "hopes.ai.gemini-api-key=",
                "hopes.ai.openai.api-key=",
                "hopes.ai.openai.chat-model=",
            )
            .run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(AiClient::class.java)
                assertThat(context.getBean(AiClient::class.java))
                    .isInstanceOf(GeminiClient::class.java)
                assertThat(context.getBean(AiClient::class.java).hasKey).isFalse()
            }
    }

    @Test
    fun `blank Gemini model cannot activate fallback`() {
        runner
            .withPropertyValues(
                "hopes.ai.chat-model=",
                "hopes.ai.openai.api-key=",
                "hopes.ai.openai.chat-model=",
            )
            .run { context -> assertThat(context.getBean(AiClient::class.java).hasKey).isFalse() }
    }

    @Test
    fun `unknown provider fails startup instead of silently selecting a provider`() {
        runner.withPropertyValues("hopes.ai.provider=typo").run { context ->
            assertThat(context).hasFailed()
        }
    }
}
