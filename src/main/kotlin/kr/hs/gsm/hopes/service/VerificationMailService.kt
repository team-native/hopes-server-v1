package kr.hs.gsm.hopes.service

import kr.hs.gsm.hopes.api.ApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

@Service
class VerificationMailService(
    private val mailSender: JavaMailSender,
    @Value("\${hopes.mail.enabled}") private val enabled: Boolean,
    @Value("\${hopes.mail.from}") private val from: String,
    @Value("\${spring.mail.password}") private val password: String,
) {
    fun sendVerificationCode(to: String, code: String, ttlMinutes: Long) {
        if (!enabled) return
        if (password.isBlank()) {
            throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "서버의 Gmail 앱 비밀번호가 설정되지 않았습니다")
        }

        val message = SimpleMailMessage().apply {
            setFrom(from)
            setTo(to)
            subject = "[Hopes] 이메일 인증번호"
            text = """
                Hopes 이메일 인증번호는 $code 입니다.

                인증번호는 ${ttlMinutes}분 동안 유효합니다.
                본인이 요청하지 않았다면 이 메일을 무시해주세요.
            """.trimIndent()
        }

        try {
            mailSender.send(message)
        } catch (_: MailException) {
            throw ApiException(HttpStatus.BAD_GATEWAY, "인증 메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요")
        }
    }
}
