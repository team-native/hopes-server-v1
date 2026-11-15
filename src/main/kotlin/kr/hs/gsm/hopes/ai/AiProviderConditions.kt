package kr.hs.gsm.hopes.ai

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/** Choose once at startup. Upstream failures never resend user data to another provider. */
internal fun useOpenAi(environment: Environment): Boolean {
    val provider = environment.getProperty("hopes.ai.provider", "auto").trim().lowercase()
    require(provider in setOf("auto", "openai", "gemini")) {
        "hopes.ai.provider must be auto, openai, or gemini"
    }
    return provider != "gemini" &&
        !environment.getProperty("hopes.ai.openai.api-key").isNullOrBlank() &&
        !environment.getProperty("hopes.ai.openai.chat-model").isNullOrBlank()
}

class OpenAiAvailableCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        useOpenAi(context.environment)
}

class GeminiFallbackCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        !useOpenAi(context.environment)
}
