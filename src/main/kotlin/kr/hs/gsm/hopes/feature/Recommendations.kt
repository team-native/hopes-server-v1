package kr.hs.gsm.hopes.feature

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kr.hs.gsm.hopes.domain.ChatMessageRepository
import kr.hs.gsm.hopes.domain.ConversationRepository
import kr.hs.gsm.hopes.domain.MessageRole
import kr.hs.gsm.hopes.service.UserService
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@Entity
@Table(name = "question_recommendations")
class QuestionRecommendation(
    @Id var id: Long = 0,
    @Column(nullable = false, length = 500) var content: String = "",
    var departmentId: Long? = null,
    var specialtyId: Long? = null,
    @Column(nullable = false) var sortOrder: Int = 0,
    @Column(nullable = false) var isActive: Boolean = true,
)

@Entity
@Table(name = "recommendation_consents")
class RecommendationConsent(
    @Id var userId: Long = 0,
    @Column(nullable = false) var enabled: Boolean = false,
    @Column(nullable = false, length = 100) var policyVersion: String = "1",
    var updatedAt: Instant? = null,
    @Version var version: Long? = null,
)

interface RecommendationRepository : JpaRepository<QuestionRecommendation, Long> {
    fun findAllByIsActiveTrueOrderBySortOrderAscIdAsc(): List<QuestionRecommendation>
}

interface ConsentRepository : JpaRepository<RecommendationConsent, Long>

data class ConsentRequest(
    val enabled: Boolean,
    @field:Size(min = 1, max = 100) val policyVersion: String,
)

data class ConsentDto(val enabled: Boolean, val policyVersion: String, val updatedAt: Instant?)

data class RecommendationDto(
    val id: Long,
    val content: String,
    val departmentId: Long?,
    val specialtyId: Long?,
)

data class RecommendationResult(
    val items: List<RecommendationDto>,
    val source: String,
    val fallbackUsed: Boolean,
    val nextRefreshToken: String,
)

@Service
class RecommendationService(
    private val users: UserService,
    private val catalog: CatalogService,
    private val recommendations: RecommendationRepository,
    private val consents: ConsentRepository,
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
    @Value("\${hopes.recommendations.policy-version:1}") private val policyVersion: String,
) {
    fun consent(email: String): ConsentDto {
        val row = consents.findById(users.requireUser(email).id!!).orElse(null)
        return ConsentDto(
            row?.enabled == true && row.policyVersion == policyVersion,
            policyVersion,
            row?.updatedAt,
        )
    }

    @Transactional
    fun consent(email: String, request: ConsentRequest): ConsentDto {
        if (request.policyVersion != policyVersion)
            fail(HttpStatus.CONFLICT, "변경된 개인화 정책을 다시 확인해주세요")
        val uid = users.requireUser(email).id!!
        val row = consents.findById(uid).orElse(RecommendationConsent(userId = uid))
        row.enabled = request.enabled
        row.policyVersion = policyVersion
        row.updatedAt = Instant.now()
        consents.save(row)
        return ConsentDto(row.enabled, policyVersion, row.updatedAt)
    }

    fun list(
        email: String,
        departmentId: Long?,
        specialtyId: Long?,
        limit: Int,
        refreshToken: String?,
        excludeIds: List<Long>,
        personalized: Boolean,
    ): RecommendationResult {
        val user = users.requireUser(email)
        catalog.context(departmentId, specialtyId)
        if (limit !in 1..20 || excludeIds.size > 100 || (refreshToken?.length ?: 0) > 100)
            fail(HttpStatus.BAD_REQUEST, "추천 개수/제외 목록/갱신 토큰을 확인해주세요")
        val all = recommendations.findAllByIsActiveTrueOrderBySortOrderAscIdAsc()
        val defaults = all.filter { it.departmentId == null && it.specialtyId == null }
        val matched =
            all.filter {
                    (it.departmentId == null || it.departmentId == departmentId) &&
                        (it.specialtyId == null || it.specialtyId == specialtyId) &&
                        (it.departmentId != null || it.specialtyId != null)
                }
                .sortedByDescending {
                    (if (it.departmentId != null) 1 else 0) + (if (it.specialtyId != null) 1 else 0)
                }
        val usingPersonalization = personalized && consent(email).enabled
        // Rank existing templates only. Never return the user's previous questions or retain
        // derived history.
        val recent =
            if (usingPersonalization)
                conversations
                    .findAllByUserIdOrderByUpdatedAtDesc(user.id!!, pageRequest(0, 3))
                    .content
                    .flatMap {
                        messages
                            .findAllByConversationIdOrderByCreatedAtDescIdDesc(
                                it.id!!,
                                pageRequest(0, 10),
                            )
                            .content
                            .filter { m -> m.role == MessageRole.USER }
                            .map { m -> m.content }
                    }
                    .joinToString(" ")
            else ""
        val seed =
            refreshToken?.let {
                runCatching { UUID.fromString(it).hashCode() }
                    .getOrElse { fail(HttpStatus.BAD_REQUEST, "갱신 토큰 형식 오류") }
            }
        val primary =
            if (departmentId == null && specialtyId == null && usingPersonalization) all
            else matched
        fun ordered(values: List<QuestionRecommendation>) =
            (if (seed != null) values.shuffled(Random(seed)) else values).sortedByDescending {
                if (recent.isEmpty()) 0
                else
                    it.content.split(Regex("\\s+")).count { word ->
                        word.length > 1 && recent.contains(word)
                    }
            }
        val candidates = (ordered(primary) + ordered(defaults)).distinctBy { it.id }
        val items =
            (candidates.filter { it.id !in excludeIds } + candidates.filter { it.id in excludeIds })
                .take(limit)
        if (items.isEmpty()) fail(HttpStatus.SERVICE_UNAVAILABLE, "추천 목록이 준비되지 않았습니다")
        val source =
            when {
                usingPersonalization -> "PERSONALIZED"
                matched.isEmpty() -> "DEFAULT"
                departmentId != null && specialtyId != null -> "COMBINED"
                departmentId != null -> "DEPARTMENT"
                else -> "SPECIALTY"
            }
        return RecommendationResult(
            items.map { RecommendationDto(it.id, it.content, it.departmentId, it.specialtyId) },
            source,
            (departmentId != null || specialtyId != null) && matched.isEmpty(),
            UUID.randomUUID().toString(),
        )
    }
}

@RestController
@RequestMapping("/api")
@Tag(name = "질문 추천")
@SecurityRequirement(name = "bearerAuth")
class RecommendationController(private val service: RecommendationService) {
    @GetMapping("/question-recommendations")
    fun list(
        auth: Authentication,
        @RequestParam(required = false) departmentId: Long?,
        @RequestParam(required = false) specialtyId: Long?,
        @RequestParam(defaultValue = "4") limit: Int,
        @RequestParam(required = false) refreshToken: String?,
        @RequestParam(defaultValue = "") excludeIds: List<Long>,
        @RequestParam(defaultValue = "false") personalized: Boolean,
    ) =
        service.list(
            auth.name,
            departmentId,
            specialtyId,
            limit,
            refreshToken,
            excludeIds,
            personalized,
        )

    @GetMapping("/privacy/recommendation-consent")
    fun consent(auth: Authentication) = service.consent(auth.name)

    @PutMapping("/privacy/recommendation-consent")
    fun consent(auth: Authentication, @Valid @RequestBody request: ConsentRequest) =
        service.consent(auth.name, request)
}
