package kr.hs.gsm.hopes.feature

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import kr.hs.gsm.hopes.domain.Inquiry
import kr.hs.gsm.hopes.domain.InquiryRepository
import kr.hs.gsm.hopes.domain.UserRole
import kr.hs.gsm.hopes.service.UserService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*

const val SUPPORT_EMAIL = "team.native.official@gmail.com"

data class InquiryReplyRequest(
    @field:NotBlank @field:Size(max = 4000) val content: String,
    @field:Pattern(regexp = "[A-Za-z0-9._:-]{1,100}") val clientRequestId: String,
)

data class InquiryDto(
    val id: Long,
    val content: String,
    val replyEmail: String,
    val createdAt: Instant,
    val answer: String?,
    val replyStatus: String,
    val repliedAt: Instant?,
)

@Service
class InquiryService(
    private val inquiries: InquiryRepository,
    private val users: UserService,
    private val tx: TransactionTemplate,
    private val mail: JavaMailSender,
    @Value("\${hopes.mail.enabled}") private val mailEnabled: Boolean,
    @Value("\${hopes.mail.from}") private val from: String,
) {
    private fun admin(email: String): Long {
        val user = users.requireUser(email)
        if (user.role != UserRole.ADMIN) fail(HttpStatus.FORBIDDEN, "운영자 권한이 필요합니다")
        return user.id!!
    }

    private fun dto(i: Inquiry) =
        InquiryDto(
            i.id!!,
            i.content,
            i.replyEmail ?: i.user.email,
            i.createdAt,
            i.answer,
            i.replyStatus,
            i.repliedAt,
        )

    fun list(email: String, page: Int, size: Int, adminView: Boolean): Map<String, Any> =
        tx.execute {
            val rows =
                if (adminView) {
                    admin(email)
                    inquiries.findAllByOrderByCreatedAtDescIdDesc(pageRequest(page, size))
                } else
                    inquiries.findAllByUserIdOrderByCreatedAtDescIdDesc(
                        users.requireUser(email).id!!,
                        pageRequest(page, size),
                    )
            mapOf("items" to rows.content.map(::dto), "hasNext" to rows.hasNext())
        }!!

    fun get(email: String, id: Long): InquiryDto =
        tx.execute {
            admin(email)
            dto(inquiries.findById(id).orElse(null) ?: fail(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다"))
        }!!

    fun reply(email: String, id: Long, request: InquiryReplyRequest): InquiryDto {
        val content = requiredText(request.content, 4000)
        // SMTP 전에 요청을 확정해 두어, 동시 요청·앱 재시도·프로세스 재시작으로 중복 발송하지 않는다.
        val claim =
            tx.execute {
                val adminId = admin(email)
                val row = inquiries.lock(id) ?: fail(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다")
                if (row.replyRequestId != null) {
                    if (row.replyRequestId != request.clientRequestId || row.answer != content)
                        fail(HttpStatus.CONFLICT, "이미 답변 요청이 등록된 문의입니다")
                    return@execute dto(row) to false
                }
                if (!mailEnabled) fail(HttpStatus.SERVICE_UNAVAILABLE, "메일 발송이 비활성화되어 있습니다")
                row.answer = content
                row.replyRequestId = request.clientRequestId
                row.replyAdminId = adminId
                row.replyStatus = "SENDING"
                inquiries.saveAndFlush(row)
                dto(row) to true
            }!!
        if (!claim.second) return claim.first

        val message =
            SimpleMailMessage().apply {
                setFrom(from)
                setReplyTo(SUPPORT_EMAIL)
                setTo(claim.first.replyEmail)
                subject = "[Hopes] 문의 #$id 답변"
                text = "$content\n\n추가 문의: $SUPPORT_EMAIL\n네이티브 팀"
            }
        try {
            mail.send(message)
        } catch (_: Exception) {
            tx.executeWithoutResult { inquiries.lock(id)?.replyStatus = "DELIVERY_UNKNOWN" }
            // SMTP 수신 후 연결이 끊겼을 수도 있어 자동 재발송하지 않는다. 운영자는 발신함을 확인한다.
            fail(HttpStatus.BAD_GATEWAY, "메일 전달 확인에 실패했습니다. 운영자는 문의 상태와 발신함을 확인해주세요")
        }
        return tx.execute {
            val row = inquiries.lock(id) ?: fail(HttpStatus.NOT_FOUND, "답변 중 문의가 삭제되었습니다")
            row.replyStatus = "SENT"
            row.repliedAt = Instant.now()
            dto(row)
        }!!
    }
}

@RestController
@RequestMapping("/api/admin/inquiries")
@Tag(name = "문의 운영자")
@SecurityRequirement(name = "bearerAuth")
class InquiryAdminController(private val service: InquiryService) {
    @GetMapping
    fun list(
        a: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = service.list(a.name, page, size, true)

    @GetMapping("/{id}")
    fun get(a: Authentication, @PathVariable id: Long) = service.get(a.name, id)

    @PostMapping("/{id}/reply")
    fun reply(
        a: Authentication,
        @PathVariable id: Long,
        @Valid @RequestBody request: InquiryReplyRequest,
    ) = service.reply(a.name, id, request)
}

@RestController
@Tag(name = "문의")
@SecurityRequirement(name = "bearerAuth")
class MyInquiryController(private val service: InquiryService) {
    @GetMapping("/api/setting/inquiries")
    fun list(
        a: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = service.list(a.name, page, size, false)
}
