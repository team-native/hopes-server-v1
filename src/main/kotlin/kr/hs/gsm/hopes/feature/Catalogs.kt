package kr.hs.gsm.hopes.feature

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.*
import kr.hs.gsm.hopes.api.ApiException
import kr.hs.gsm.hopes.service.UserService
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

fun fail(status: HttpStatus, message: String): Nothing = throw ApiException(status, message)

fun pageRequest(page: Int, size: Int): org.springframework.data.domain.PageRequest {
    if (page < 0 || size !in 1..100) fail(HttpStatus.BAD_REQUEST, "페이지는 0 이상, 크기는 1~100이어야 합니다")
    return org.springframework.data.domain.PageRequest.of(page, size)
}

fun requiredText(value: String, max: Int): String =
    value.trim().also {
        if (it.isBlank()) fail(HttpStatus.BAD_REQUEST, "내용을 입력해주세요")
        if (it.length > max) fail(HttpStatus.PAYLOAD_TOO_LARGE, "내용은 ${max}자 이하여야 합니다")
    }

@Entity
@Table(name = "chat_categories")
class ChatCategory(
    @Id var id: Long = 0,
    @Column(nullable = false, length = 20) var kind: String = "",
    @Column(nullable = false, length = 100) var name: String = "",
    @Column(nullable = false, length = 500) var description: String = "",
    @Column(nullable = false) var sortOrder: Int = 0,
    @Column(nullable = false) var isActive: Boolean = true,
)

@Entity
@Table(name = "chat_preferences")
class ChatPreference(
    @Id var userId: Long = 0,
    var departmentId: Long? = null,
    var specialtyId: Long? = null,
    @Version var version: Long? = null,
)

interface CategoryRepository : JpaRepository<ChatCategory, Long> {
    fun findAllByKindAndIsActiveTrueOrderBySortOrderAscIdAsc(kind: String): List<ChatCategory>
}

interface PreferenceRepository : JpaRepository<ChatPreference, Long>

data class CategoryDto(
    val id: Long,
    val name: String,
    val description: String,
    val sortOrder: Int,
    val isActive: Boolean,
)

data class PreferenceDto(
    val departmentId: Long?,
    val specialtyId: Long?,
    val invalidSelection: Boolean = false,
)

@Service
class CatalogService(
    private val categories: CategoryRepository,
    private val preferences: PreferenceRepository,
    private val users: UserService,
) {
    fun list(kind: String) =
        mapOf(
            "items" to
                categories.findAllByKindAndIsActiveTrueOrderBySortOrderAscIdAsc(kind).map {
                    CategoryDto(it.id, it.name, it.description, it.sortOrder, it.isActive)
                }
        )

    fun validate(id: Long?, kind: String): ChatCategory? {
        if (id == null) return null
        return categories.findById(id).orElse(null)?.takeIf { it.kind == kind && it.isActive }
            ?: fail(HttpStatus.CONFLICT, "삭제되거나 비활성화된 카테고리입니다")
    }

    fun context(departmentId: Long?, specialtyId: Long?): String {
        val department = validate(departmentId, "DEPARTMENT")
        val specialty = validate(specialtyId, "SPECIALTY")
        return "선택 학과: ${department?.name ?: "전체"}\n관심 전공: ${specialty?.name ?: "전체"}"
    }

    @Transactional(readOnly = true)
    fun get(email: String): PreferenceDto {
        val row =
            preferences.findById(users.requireUser(email).id!!).orElse(null)
                ?: return PreferenceDto(null, null)
        val invalid = runCatching { context(row.departmentId, row.specialtyId) }.isFailure
        return PreferenceDto(row.departmentId, row.specialtyId, invalid)
    }

    @Transactional
    fun update(email: String, body: JsonNode): PreferenceDto {
        if (
            !body.isObject ||
                body.fieldNames().asSequence().any { it !in setOf("departmentId", "specialtyId") }
        )
            fail(HttpStatus.BAD_REQUEST, "학과와 전공 ID만 전달해주세요")
        val uid = users.requireUser(email).id!!
        val row = preferences.findById(uid).orElse(ChatPreference(userId = uid))
        fun read(key: String, old: Long?): Long? {
            if (!body.has(key)) return old
            val node = body[key]
            if (node.isNull) return null
            if (!node.isIntegralNumber || !node.canConvertToLong() || node.asLong() <= 0)
                fail(HttpStatus.BAD_REQUEST, "ID 형식이 올바르지 않습니다")
            return node.asLong()
        }
        val department = read("departmentId", row.departmentId)
        val specialty = read("specialtyId", row.specialtyId)
        context(department, specialty)
        row.departmentId = department
        row.specialtyId = specialty
        preferences.save(row)
        return PreferenceDto(department, specialty)
    }
}

@RestController
@RequestMapping("/api")
@Tag(name = "학과·전공")
@SecurityRequirement(name = "bearerAuth")
class CatalogController(private val service: CatalogService) {
    @GetMapping("/departments") fun departments() = service.list("DEPARTMENT")

    @GetMapping("/specialties") fun specialties() = service.list("SPECIALTY")

    @GetMapping("/chat-preferences") fun get(auth: Authentication) = service.get(auth.name)

    @PatchMapping("/chat-preferences")
    fun patch(auth: Authentication, @RequestBody body: JsonNode) = service.update(auth.name, body)
}
