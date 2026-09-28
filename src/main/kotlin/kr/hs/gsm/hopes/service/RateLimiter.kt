package kr.hs.gsm.hopes.service

import kr.hs.gsm.hopes.api.ApiException
import kr.hs.gsm.hopes.domain.RateLimitWindow
import kr.hs.gsm.hopes.domain.RateLimitWindowRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * MySQL/H2에 고정 윈도우 카운터를 저장하는 요청 제한기.
 * 키는 SHA-256으로 저장해 이메일/IP 원문을 남기지 않으며 여러 서버 인스턴스가 같은 제한을 공유한다.
 */
@Component
class RateLimiter(
    private val repository: RateLimitWindowRepository,
    transactionManager: PlatformTransactionManager,
    @Value("\${hopes.rate-limit.messages-per-minute}") private val messagesPerMinute: Int,
    @Value("\${hopes.rate-limit.login-attempts-per-minute}") private val loginAttemptsPerMinute: Int,
    @Value("\${hopes.rate-limit.verification-requests-per-minute}") private val verificationRequestsPerMinute: Int,
    @Value("\${hopes.rate-limit.verification-attempts-per-minute}") private val verificationAttemptsPerMinute: Int,
    @Value("\${hopes.rate-limit.auth-requests-per-ip-per-minute}") private val authRequestsPerIpPerMinute: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lastCleanupMinute = AtomicLong(Long.MIN_VALUE)
    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun checkMessage(email: String) {
        check("message", email.lowercase(), messagesPerMinute, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요")
    }

    fun checkVerificationRequest(email: String, clientAddress: String) {
        checkAuthIp(clientAddress)
        check(
            "verification-request",
            email.lowercase(),
            verificationRequestsPerMinute,
            "인증번호를 너무 자주 요청했습니다. 잠시 후 다시 시도해주세요",
        )
    }

    fun checkVerificationAttempt(email: String, clientAddress: String) {
        checkAuthIp(clientAddress)
        check(
            "verification-attempt",
            email.lowercase(),
            verificationAttemptsPerMinute,
            "인증번호 확인을 너무 많이 시도했습니다. 잠시 후 다시 시도해주세요",
        )
    }

    fun checkLogin(identifier: String, clientAddress: String) {
        checkAuthIp(clientAddress)
        check(
            "login",
            identifier.trim().lowercase(),
            loginAttemptsPerMinute,
            "로그인을 너무 많이 시도했습니다. 잠시 후 다시 시도해주세요",
        )
    }

    fun checkLoginAccount(email: String) {
        check(
            "login-account",
            email.lowercase(),
            loginAttemptsPerMinute,
            "로그인을 너무 많이 시도했습니다. 잠시 후 다시 시도해주세요",
        )
    }

    fun resetLogin(username: String, email: String) {
        repository.deleteAllById(
            listOf(
                keyHash("login", username.trim().lowercase()),
                keyHash("login", email.lowercase()),
                keyHash("login-account", email.lowercase()),
            ).distinct()
        )
    }

    private fun checkAuthIp(clientAddress: String) {
        check(
            "auth-ip",
            clientAddress.ifBlank { "unknown" },
            authRequestsPerIpPerMinute,
            "인증 요청이 너무 많습니다. 잠시 후 다시 시도해주세요",
        )
    }

    private fun check(scope: String, subject: String, limit: Int, message: String) {
        if (limit <= 0) return
        val minute = Instant.now().epochSecond / 60
        cleanupExpired(minute)
        val count = incrementWithRetry(keyHash(scope, subject), minute)
        if (count > limit) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, message)
    }

    private fun incrementWithRetry(keyHash: String, minute: Long): Int {
        var lastFailure: RuntimeException? = null
        repeat(MAX_RETRIES) {
            try {
                return transactionTemplate.execute {
                    val window = repository.findById(keyHash).orElse(null)
                        ?: RateLimitWindow(keyHash = keyHash, windowMinute = minute, requestCount = 0)
                    if (window.windowMinute != minute) {
                        window.windowMinute = minute
                        window.requestCount = 0
                    }
                    window.requestCount += 1
                    repository.saveAndFlush(window).requestCount
                } ?: throw IllegalStateException("요청 제한 카운터 저장 결과가 없습니다")
            } catch (failure: ObjectOptimisticLockingFailureException) {
                lastFailure = failure
            } catch (failure: DataIntegrityViolationException) {
                lastFailure = failure
            }
        }
        log.error("요청 제한 카운터를 갱신하지 못했습니다", lastFailure)
        throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요")
    }

    private fun cleanupExpired(minute: Long) {
        if (lastCleanupMinute.getAndSet(minute) == minute) return
        try {
            transactionTemplate.executeWithoutResult { repository.deleteExpiredBefore(minute - 1) }
        } catch (failure: RuntimeException) {
            log.warn("만료된 요청 제한 카운터 정리에 실패했습니다: {}", failure.message)
        }
    }

    private fun keyHash(scope: String, subject: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$scope\u0000$subject".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_RETRIES = 5
    }
}
