package kr.hs.gsm.hopes.feature

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.temporal.ChronoUnit
import kr.hs.gsm.hopes.domain.User
import kr.hs.gsm.hopes.domain.UserRole
import kr.hs.gsm.hopes.service.UserService
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

// 커뮤니티 운영자 기능: 신고 목록·상세 조회, 숨김·복구, 경고·글쓰기 정지, 처리 완료·기각.
//
// 권한 검사는 두 겹이다.
//  1. SecurityConfig: /api/admin/ 아래 경로는 ROLE_ADMIN 이 아니면 403 (컨트롤러까지 오지 않음)
//  2. ModerationService.requireAdmin: 서비스를 다른 곳에서 호출해도 운영자만 쓸 수 있게 다시 확인
//
// 익명성: 운영자 응답에도 신고자·작성자의 계정 정보는 넣지 않는다.
// 제재는 "이 신고 대상의 작성자"에게 걸기 때문에 운영자가 작성자가 누구인지 알 필요가 없다.

enum class ModerationAction {
    /** 대상 글·댓글을 일반 사용자에게서 숨긴다(내용은 보존). */
    HIDE,
    /** 숨김을 푼다. */
    RESTORE,
    /** 작성자에게 경고를 기록한다. */
    WARN,
    /** 작성자의 커뮤니티 글쓰기를 days 일 동안 막는다. */
    SUSPEND,
    /** 신고를 처리 완료로 닫는다. */
    RESOLVE,
    /** 신고를 기각으로 닫는다. */
    DISMISS,
    /** 닫힌 신고를 다시 연다. */
    REOPEN,
}

enum class SanctionType { WARNING, WRITE_BAN }

@Entity
@Table(name = "community_moderation_actions")
class ModerationActionLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var reportId: Long = 0,
    /** 처리한 운영자. 운영자 계정이 삭제되면 NULL 이 되고 기록은 남는다. */
    var adminId: Long? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var action: ModerationAction = ModerationAction.RESOLVE,
    @Column(nullable = false, length = 2000) var memo: String = "",
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "community_sanctions")
class CommunitySanction(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var userId: Long = 0,
    var reportId: Long? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var sanctionType: SanctionType = SanctionType.WARNING,
    @Column(nullable = false, length = 2000) var reason: String = "",
    @Column(nullable = false) var startsAt: Instant = Instant.now(),
    /** 경고는 NULL, 글쓰기 정지는 끝나는 시각. */
    var endsAt: Instant? = null,
    var createdBy: Long? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
)

interface ModerationActionRepository : JpaRepository<ModerationActionLog, Long> {
    fun findAllByReportIdOrderByCreatedAtAscIdAsc(reportId: Long): List<ModerationActionLog>
}

interface SanctionRepository : JpaRepository<CommunitySanction, Long> {
    fun findFirstByUserIdAndSanctionTypeAndEndsAtAfterOrderByEndsAtDesc(
        userId: Long,
        sanctionType: SanctionType,
        now: Instant,
    ): CommunitySanction?

    fun countByUserId(userId: Long): Long
}

data class ModerationRequest(
    val action: String,
    @field:Size(max = 2000) val memo: String? = null,
    /** SUSPEND 일 때만 사용. 1~365일. */
    val days: Int? = null,
)

data class ReportSummaryDto(
    val id: Long,
    val targetType: String,
    val targetId: Long,
    val reasonCode: String,
    val status: ReportStatus,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val sameTargetReportCount: Long,
)

/** 신고 시점에 복사해 둔 내용. 보관 기간이 지나면 비워지고 purgedAt 이 채워진다. */
data class EvidenceDto(val title: String?, val content: String?, val purgedAt: Instant?)

/** 지금 대상의 상태. exists=false 면 작성자 탈퇴 등으로 행 자체가 사라진 것이다. */
data class TargetStateDto(
    val exists: Boolean,
    val deleted: Boolean,
    val hidden: Boolean,
    val title: String?,
    val content: String?,
)

data class ModerationActionDto(
    val id: Long,
    val action: ModerationAction,
    val memo: String,
    val adminId: Long?,
    val createdAt: Instant,
)

data class ReportDetailDto(
    val id: Long,
    val targetType: String,
    val targetId: Long,
    val targetPostId: Long?,
    val reasonCode: String,
    val detail: String,
    val status: ReportStatus,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val evidence: EvidenceDto,
    val currentTarget: TargetStateDto,
    val sameTargetReportCount: Long,
    /** 이 대상 작성자가 지금까지 받은 경고·정지 횟수. 작성자가 누구인지는 알려주지 않는다. */
    val authorSanctionCount: Long,
    val actions: List<ModerationActionDto>,
)

@Service
@Transactional
class ModerationService(
    private val users: UserService,
    private val reports: ReportRepository,
    private val posts: PostRepository,
    private val comments: CommentRepository,
    private val actions: ModerationActionRepository,
    private val sanctions: SanctionRepository,
) {
    private fun requireAdmin(email: String): User =
        users.requireUser(email).also {
            if (it.role != UserRole.ADMIN) fail(HttpStatus.FORBIDDEN, "운영자 권한이 필요합니다")
        }

    private fun report(id: Long) =
        reports.findById(id).orElse(null) ?: fail(HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다")

    @Transactional(readOnly = true)
    fun list(email: String, status: String?, page: Int, size: Int): ResultPage<ReportSummaryDto> {
        requireAdmin(email)
        val pageable = pageRequest(page, size)
        val rows =
            if (status.isNullOrBlank()) reports.findAllByOrderByCreatedAtDescIdDesc(pageable)
            else reports.findAllByStatusOrderByCreatedAtDescIdDesc(parseStatus(status), pageable)
        return ResultPage(
            rows.content.map {
                ReportSummaryDto(
                    it.id!!,
                    it.targetType,
                    it.targetId,
                    it.reasonCode,
                    it.status,
                    it.createdAt,
                    it.resolvedAt,
                    reports.countByTargetTypeAndTargetId(it.targetType, it.targetId),
                )
            },
            page,
            size,
            rows.hasNext(),
        )
    }

    @Transactional(readOnly = true)
    fun detail(email: String, id: Long): ReportDetailDto {
        requireAdmin(email)
        return detailOf(report(id))
    }

    fun act(email: String, id: Long, r: ModerationRequest): ReportDetailDto {
        val admin = requireAdmin(email)
        // State checks and actions on the same report must be serialized.
        val report = reports.lockForAction(id)
            ?: fail(HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다")
        val action =
            runCatching { ModerationAction.valueOf(r.action.trim().uppercase()) }.getOrNull()
                ?: fail(
                    HttpStatus.BAD_REQUEST,
                    "action은 ${ModerationAction.entries.joinToString("/")} 중 하나여야 합니다",
                )
        val memo = r.memo.orEmpty().trim()
        val now = Instant.now()
        when (action) {
            ModerationAction.HIDE -> setHidden(report, true)
            ModerationAction.RESTORE -> setHidden(report, false)
            ModerationAction.WARN ->
                sanction(report, admin, SanctionType.WARNING, endsAt = null, memo = memo)
            ModerationAction.SUSPEND -> {
                val days = r.days ?: fail(HttpStatus.BAD_REQUEST, "정지 기간(days)을 입력해주세요")
                if (days !in 1..365) fail(HttpStatus.BAD_REQUEST, "정지 기간은 1~365일이어야 합니다")
                sanction(report, admin, SanctionType.WRITE_BAN, now.plus(days.toLong(), ChronoUnit.DAYS), memo)
            }
            ModerationAction.RESOLVE -> close(report, ReportStatus.RESOLVED, now)
            ModerationAction.DISMISS -> close(report, ReportStatus.DISMISSED, now)
            ModerationAction.REOPEN -> {
                if (report.status == ReportStatus.RECEIVED) fail(HttpStatus.CONFLICT, "이미 열려 있는 신고입니다")
                if (report.evidencePurgedAt != null)
                    fail(HttpStatus.CONFLICT, "증거 보관 기간이 지나 다시 열 수 없습니다")
                report.status = ReportStatus.RECEIVED
                report.resolvedAt = null
            }
        }
        // 어떤 조치든 누가·언제·무엇을 했는지 남긴다.
        actions.save(ModerationActionLog(reportId = report.id!!, adminId = admin.id, action = action, memo = memo))
        return detailOf(report)
    }

    private fun close(report: CommunityReport, status: ReportStatus, now: Instant) {
        if (report.status != ReportStatus.RECEIVED)
            fail(HttpStatus.CONFLICT, "이미 처리된 신고입니다. 바꾸려면 REOPEN 후 다시 처리해주세요")
        report.status = status
        report.resolvedAt = now
    }

    private fun setHidden(report: CommunityReport, hidden: Boolean) {
        when (report.targetType) {
            "POST" -> posts.lockForModeration(report.targetId)?.let { it.hidden = hidden }
            else -> comments.lockForModeration(report.targetId)?.let { it.hidden = hidden }
        } ?: fail(HttpStatus.CONFLICT, "대상이 완전히 삭제되어 숨김 상태를 바꿀 수 없습니다")
    }

    private fun sanction(
        report: CommunityReport,
        admin: User,
        type: SanctionType,
        endsAt: Instant?,
        memo: String,
    ) {
        val author =
            authorOf(report) ?: fail(HttpStatus.CONFLICT, "작성자 계정이 삭제되어 제재할 수 없습니다")
        if (author == admin.id) fail(HttpStatus.BAD_REQUEST, "본인에게는 제재를 적용할 수 없습니다")
        sanctions.save(
            CommunitySanction(
                userId = author,
                reportId = report.id,
                sanctionType = type,
                reason = memo,
                endsAt = endsAt,
                createdBy = admin.id,
            )
        )
    }

    // 운영자는 작성자가 삭제했거나 숨긴 대상도 볼 수 있어야 하므로 필터 없는 findById 를 쓴다.
    private fun targetPost(report: CommunityReport) =
        if (report.targetType == "POST") posts.findById(report.targetId).orElse(null) else null

    private fun targetComment(report: CommunityReport) =
        if (report.targetType == "COMMENT") comments.findById(report.targetId).orElse(null) else null

    private fun authorOf(report: CommunityReport): Long? =
        targetPost(report)?.authorId ?: targetComment(report)?.authorId

    private fun detailOf(report: CommunityReport): ReportDetailDto {
        val post = targetPost(report)
        val comment = targetComment(report)
        val target =
            when {
                post != null -> TargetStateDto(true, post.deleted, post.hidden, post.title, post.content)
                comment != null ->
                    TargetStateDto(true, comment.deleted, comment.hidden, null, comment.content)
                else -> TargetStateDto(false, true, false, null, null)
            }
        return ReportDetailDto(
            id = report.id!!,
            targetType = report.targetType,
            targetId = report.targetId,
            targetPostId = report.targetPostId,
            reasonCode = report.reasonCode,
            detail = report.detail,
            status = report.status,
            createdAt = report.createdAt,
            resolvedAt = report.resolvedAt,
            evidence = EvidenceDto(report.snapshotTitle, report.snapshotContent, report.evidencePurgedAt),
            currentTarget = target,
            sameTargetReportCount = reports.countByTargetTypeAndTargetId(report.targetType, report.targetId),
            authorSanctionCount = authorOf(report)?.let(sanctions::countByUserId) ?: 0,
            actions =
                actions.findAllByReportIdOrderByCreatedAtAscIdAsc(report.id!!).map {
                    ModerationActionDto(it.id!!, it.action, it.memo, it.adminId, it.createdAt)
                },
        )
    }

    private fun parseStatus(value: String) =
        runCatching { ReportStatus.valueOf(value.trim().uppercase()) }.getOrNull()
            ?: fail(HttpStatus.BAD_REQUEST, "status는 RECEIVED/RESOLVED/DISMISSED 중 하나여야 합니다")
}

/** 처리 완료·기각 후 보관 기간이 지난 신고 증거를 매일 새벽에 지운다. 처리 전(RECEIVED) 신고는 지우지 않는다. */
@Component
@Transactional
class ReportEvidenceRetention(
    private val reports: ReportRepository,
    @Value("\${hopes.community.evidence-retention-days:365}") private val retentionDays: Long,
) {
    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul") fun scheduled() = purge(Instant.now())

    fun purge(now: Instant): Int =
        reports.purgeEvidence(now.minus(retentionDays, ChronoUnit.DAYS), now)
}

@RestController
@RequestMapping("/api/admin/community")
@Tag(name = "커뮤니티 운영자")
@SecurityRequirement(name = "bearerAuth")
class ModerationController(private val s: ModerationService) {
    @GetMapping("/reports")
    fun reports(
        a: Authentication,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = s.list(a.name, status, page, size)

    @GetMapping("/reports/{reportId}")
    fun report(a: Authentication, @PathVariable reportId: Long) = s.detail(a.name, reportId)

    @PostMapping("/reports/{reportId}/actions")
    fun act(
        a: Authentication,
        @PathVariable reportId: Long,
        @Valid @RequestBody r: ModerationRequest,
    ) = s.act(a.name, reportId, r)
}
