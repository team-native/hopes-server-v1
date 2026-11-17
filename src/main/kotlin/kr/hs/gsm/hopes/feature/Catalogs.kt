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
    @ElementCollection
    @CollectionTable(name = "chat_preference_specialties", joinColumns = [JoinColumn(name = "user_id")])
    @OrderColumn(name = "selection_order")
    @Column(name = "specialty_id", nullable = false)
    var specialtyIds: MutableList<Long> = mutableListOf(),
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
    val specialtyIds: List<Long> = emptyList(),
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

    fun selection(specialtyId: Long?, specialtyIds: List<Long?>?): List<Long> {
        if ((specialtyIds?.size ?: 0) > 20 || specialtyId?.let { it <= 0 } == true || specialtyIds?.any { it == null || it <= 0 } == true)
            fail(HttpStatus.BAD_REQUEST, "전공 ID는 양수이며 최대 20개까지 선택할 수 있습니다")
        val ids = specialtyIds?.filterNotNull()?.distinct() ?: listOfNotNull(specialtyId)
        if (specialtyIds != null && specialtyId != null && ids.firstOrNull() != specialtyId)
            fail(HttpStatus.BAD_REQUEST, "specialtyId는 specialtyIds의 첫 번째 항목과 같아야 합니다")
        return ids
    }

    fun context(departmentId: Long?, specialtyId: Long?, specialtyIds: List<Long>? = null): String {
        val department = validate(departmentId, "DEPARTMENT")
        val specialties = selection(specialtyId, specialtyIds).map { validate(it, "SPECIALTY")!!.name }
        return "선택 학과: ${department?.name ?: "전체"}\n관심 전공: ${specialties.joinToString(", ").ifEmpty { "전체" }}"
    }

    @Transactional(readOnly = true)
    fun get(email: String): PreferenceDto {
        val row =
            preferences.findById(users.requireUser(email).id!!).orElse(null)
                ?: return PreferenceDto(null, null)
        val ids = row.specialtyIds.toList()
        val invalid = runCatching { context(row.departmentId, row.specialtyId, ids) }.isFailure
        return PreferenceDto(row.departmentId, row.specialtyId, invalid, ids)
    }

    @Transactional
    fun update(email: String, body: JsonNode): PreferenceDto {
        if (
            !body.isObject ||
                body.fieldNames().asSequence().any { it !in setOf("departmentId", "specialtyId", "specialtyIds") }
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
        val ids = if (body.has("specialtyIds")) {
            val values = body["specialtyIds"]
            if (!values.isArray || values.size() > 20 || values.any { !it.isIntegralNumber || !it.canConvertToLong() })
                fail(HttpStatus.BAD_REQUEST, "specialtyIds는 최대 20개의 정수 배열이어야 합니다")
            if (body.has("specialtyId") && body["specialtyId"].isNull && values.size() > 0)
                fail(HttpStatus.BAD_REQUEST, "단일 전공과 전공 목록이 일치하지 않습니다")
            selection(if (body.has("specialtyId")) read("specialtyId", null) else null, values.map { it.asLong() })
        } else if (body.has("specialtyId")) selection(read("specialtyId", null), null)
        else row.specialtyIds.toList()
        val specialty = ids.firstOrNull()
        context(department, specialty, ids)
        row.departmentId = department
        row.specialtyId = specialty
        row.specialtyIds.clear()
        row.specialtyIds.addAll(ids)
        preferences.save(row)
        return PreferenceDto(department, specialty, specialtyIds = ids)
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
