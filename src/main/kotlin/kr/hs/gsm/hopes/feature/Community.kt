package kr.hs.gsm.hopes.feature

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.time.Instant
import kr.hs.gsm.hopes.service.RateLimiter
import kr.hs.gsm.hopes.service.UserService
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@Entity
@Table(name = "community_boards")
class CommunityBoard(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false, length = 100) var name: String = "",
    @Column(unique = true) var cohort: Int? = null,
    @Column(nullable = false) var sortOrder: Int = 0,
)

@Entity
@Table(
    name = "community_posts",
    uniqueConstraints = [UniqueConstraint(columnNames = ["author_id", "client_request_id"])],
)
class CommunityPost(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var boardId: Long = 0,
    @Column(nullable = false) var authorId: Long = 0,
    @Column(nullable = false, length = 200) var title: String = "",
    @Column(nullable = false, length = 10000) var content: String = "",
    @Column(length = 100) var clientRequestId: String? = null,
    @Column(nullable = false) var deleted: Boolean = false,
    /** 운영자 숨김. 작성자 삭제(deleted)와 달리 내용을 지우지 않고 복구할 수 있다. */
    @Column(nullable = false) var hidden: Boolean = false,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(
    name = "community_comments",
    uniqueConstraints = [UniqueConstraint(columnNames = ["author_id", "client_request_id"])],
)
class CommunityComment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var postId: Long = 0,
    @Column(nullable = false) var authorId: Long = 0,
    @Column(nullable = false, length = 2000) var content: String = "",
    @Column(length = 100) var clientRequestId: String? = null,
    @Column(nullable = false) var deleted: Boolean = false,
    /** 운영자 숨김. 작성자 삭제(deleted)와 달리 내용을 지우지 않고 복구할 수 있다. */
    @Column(nullable = false) var hidden: Boolean = false,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(
    name = "community_likes",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "post_id"])],
)
class CommunityLike(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var userId: Long = 0,
    @Column(nullable = false) var postId: Long = 0,
)

@Entity
@Table(
    name = "community_reports",
    uniqueConstraints =
        [UniqueConstraint(columnNames = ["reporter_id", "target_type", "target_id"])],
)
class CommunityReport(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var reporterId: Long = 0,
    @Column(nullable = false, length = 10) var targetType: String = "",
    @Column(nullable = false) var targetId: Long = 0,
    @Column(nullable = false, length = 50) var reasonCode: String = "",
    @Column(nullable = false, length = 2000) var detail: String = "",
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ReportStatus = ReportStatus.RECEIVED,
    /** 댓글 신고일 때 운영자가 문맥을 볼 수 있도록 원 게시글 ID를 함께 둔다. */
    var targetPostId: Long? = null,
    /** 신고 시점 증거. 작성자가 글을 삭제·수정해도 남고, 처리 후 보관 기간이 지나면 지운다. */
    @Column(length = 200) var snapshotTitle: String? = null,
    @Column(length = 10000) var snapshotContent: String? = null,
    var resolvedAt: Instant? = null,
    var evidencePurgedAt: Instant? = null,
)

enum class ReportStatus { RECEIVED, RESOLVED, DISMISSED }

interface BoardRepository : JpaRepository<CommunityBoard, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from CommunityBoard b where b.cohort is null")
    fun lockGeneralBoard(): CommunityBoard?

    fun findAllByOrderBySortOrderAscIdAsc(): List<CommunityBoard>

    fun existsByCohort(cohort: Int): Boolean

    @Query("select distinct u.cohort from User u where u.cohort is not null and u.cohort > 0")
    fun cohorts(): List<Int>
}

interface PostRepository : JpaRepository<CommunityPost, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommunityPost p where p.id = :id")
    fun lockForModeration(@Param("id") id: Long): CommunityPost?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommunityPost p where p.id = :id and p.deleted = false and p.hidden = false")
    fun lock(@Param("id") id: Long): CommunityPost?

    @Query(
        "select p from CommunityPost p where p.deleted = false and p.hidden = false and (:board is null or p.boardId = :board) and (lower(p.title) like :keyword escape '!' or lower(p.content) like :keyword escape '!') order by p.createdAt desc, p.id desc"
    )
    fun search(
        @Param("board") board: Long?,
        @Param("keyword") keyword: String,
        pageable: Pageable,
    ): Slice<CommunityPost>

    fun findAllByAuthorIdAndDeletedFalseAndHiddenFalseOrderByCreatedAtDescIdDesc(
        authorId: Long,
        pageable: Pageable,
    ): Slice<CommunityPost>

    fun findByAuthorIdAndClientRequestId(authorId: Long, clientRequestId: String): CommunityPost?
}

interface CommentRepository : JpaRepository<CommunityComment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommunityComment c where c.id = :id")
    fun lockForModeration(@Param("id") id: Long): CommunityComment?

    fun findAllByPostIdAndDeletedFalseAndHiddenFalseOrderByCreatedAtAscIdAsc(
        postId: Long,
        pageable: Pageable,
    ): Slice<CommunityComment>

    fun findAllByAuthorIdAndDeletedFalseAndHiddenFalseOrderByCreatedAtDescIdDesc(
        authorId: Long,
        pageable: Pageable,
    ): Slice<CommunityComment>

    fun countByPostIdAndDeletedFalseAndHiddenFalse(postId: Long): Long

    fun findByAuthorIdAndClientRequestId(authorId: Long, clientRequestId: String): CommunityComment?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommunityComment c where c.id = :id and c.deleted = false and c.hidden = false")
    fun lock(@Param("id") id: Long): CommunityComment?
}

interface LikeRepository : JpaRepository<CommunityLike, Long> {
    fun findByUserIdAndPostId(userId: Long, postId: Long): CommunityLike?

    fun countByPostId(postId: Long): Long
}

interface ReportRepository : JpaRepository<CommunityReport, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CommunityReport r where r.id = :id")
    fun lockForAction(@Param("id") id: Long): CommunityReport?

    fun existsByReporterIdAndTargetTypeAndTargetId(
        reporterId: Long,
        targetType: String,
        targetId: Long,
    ): Boolean

    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): Slice<CommunityReport>

    fun findAllByStatusOrderByCreatedAtDescIdDesc(
        status: ReportStatus,
        pageable: Pageable,
    ): Slice<CommunityReport>

    fun countByTargetTypeAndTargetId(targetType: String, targetId: Long): Long

    /** 처리 완료 후 보관 기간이 지난 신고의 증거(신고 시점 내용과 신고 상세)를 지운다. */
    @Modifying
    @Query(
        "update CommunityReport r set r.snapshotTitle = null, r.snapshotContent = null, r.detail = '', r.evidencePurgedAt = :now where r.status <> kr.hs.gsm.hopes.feature.ReportStatus.RECEIVED and r.resolvedAt < :cutoff and r.evidencePurgedAt is null"
    )
    fun purgeEvidence(@Param("cutoff") cutoff: Instant, @Param("now") now: Instant): Int
}

data class BoardDto(val id: Long, val name: String, val cohort: Int?, val sortOrder: Int)

data class PostRequest(
    val boardId: Long,
    val title: String,
    val content: String,
    @field:Size(max = 100) val clientRequestId: String? = null,
)

data class PostUpdate(val title: String? = null, val content: String? = null)

data class CommentRequest(
    val content: String,
    @field:Size(max = 100) val clientRequestId: String? = null,
)

data class ReportRequest(
    val targetType: String,
    val targetId: Long,
    val reasonCode: String,
    val detail: String? = null,
)

data class PostDto(
    val id: Long,
    val boardId: Long,
    val title: String,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val likeCount: Long,
    val commentCount: Long,
    val likedByMe: Boolean,
    val isMine: Boolean,
    val authorLabel: String = "익명",
)

data class PostPreview(
    val id: Long,
    val boardId: Long,
    val title: String,
    val preview: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val likeCount: Long,
    val commentCount: Long,
    val likedByMe: Boolean,
    val isMine: Boolean,
    val authorLabel: String = "익명",
)

data class CommentDto(
    val id: Long,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isMine: Boolean,
    val authorLabel: String = "익명",
)

data class MyCommentDto(
    val id: Long,
    val postId: Long,
    val postTitle: String?,
    val content: String,
    val createdAt: Instant,
    val isMine: Boolean = true,
)

data class ResultPage<T>(val items: List<T>, val page: Int, val size: Int, val hasNext: Boolean)

@Service
@Transactional
class CommunityService(
    private val users: UserService,
    private val boards: BoardRepository,
    private val posts: PostRepository,
    private val comments: CommentRepository,
    private val likes: LikeRepository,
    private val reports: ReportRepository,
    private val sanctions: SanctionRepository,
    private val limits: RateLimiter,
) {
    fun boards(): Map<String, List<BoardDto>> {
        boards.lockGeneralBoard() ?: fail(HttpStatus.SERVICE_UNAVAILABLE, "전체 게시판이 준비되지 않았습니다")
        boards.cohorts().forEach {
            if (!boards.existsByCohort(it))
                boards.save(CommunityBoard(name = "${it}기", cohort = it, sortOrder = it))
        }
        return mapOf(
            "items" to
                boards.findAllByOrderBySortOrderAscIdAsc().map {
                    BoardDto(it.id!!, it.name, it.cohort, it.sortOrder)
                }
        )
    }

    private fun uid(email: String) = users.requireUser(email).id!!

    private fun board(id: Long) =
        boards.findById(id).orElse(null) ?: fail(HttpStatus.NOT_FOUND, "게시판을 찾을 수 없습니다")

    private fun post(id: Long) =
        posts.findById(id).orElse(null)?.takeUnless { it.deleted || it.hidden }
            ?: fail(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다")

    private fun lockedPost(id: Long) =
        posts.lock(id) ?: fail(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다")

    private fun own(author: Long, uid: Long) {
        if (author != uid) fail(HttpStatus.FORBIDDEN, "본인이 작성한 내용만 변경할 수 있습니다")
    }

    /** 운영자에게 글쓰기 정지를 받은 동안에는 글·댓글 작성과 수정을 막는다. 열람·좋아요·신고·삭제는 허용한다. */
    private fun ensureCanWrite(uid: Long) {
        val ban =
            sanctions.findFirstByUserIdAndSanctionTypeAndEndsAtAfterOrderByEndsAtDesc(
                uid,
                SanctionType.WRITE_BAN,
                Instant.now(),
            ) ?: return
        fail(HttpStatus.FORBIDDEN, "커뮤니티 글쓰기가 ${ban.endsAt}까지 제한되었습니다")
    }

    private fun dto(p: CommunityPost, uid: Long) =
        PostDto(
            p.id!!,
            p.boardId,
            p.title,
            p.content,
            p.createdAt,
            p.updatedAt,
            likes.countByPostId(p.id!!),
            comments.countByPostIdAndDeletedFalseAndHiddenFalse(p.id!!),
            likes.findByUserIdAndPostId(uid, p.id!!) != null,
            p.authorId == uid,
        )

    private fun preview(p: CommunityPost, uid: Long): PostPreview {
        val d = dto(p, uid)
        return PostPreview(
            d.id,
            d.boardId,
            d.title,
            d.content.take(200),
            d.createdAt,
            d.updatedAt,
            d.likeCount,
            d.commentCount,
            d.likedByMe,
            d.isMine,
        )
    }

    private fun commentDto(c: CommunityComment, uid: Long) =
        CommentDto(c.id!!, c.content, c.createdAt, c.updatedAt, c.authorId == uid)

    fun list(
        email: String,
        boardId: Long?,
        keyword: String?,
        page: Int,
        size: Int,
    ): ResultPage<PostPreview> {
        val user = uid(email)
        boardId?.let { board(it) }
        if ((keyword?.length ?: 0) > 200) fail(HttpStatus.BAD_REQUEST, "검색어는 200자 이하여야 합니다")
        val escaped =
            keyword
                .orEmpty()
                .trim()
                .lowercase()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_")
        val rows = posts.search(boardId, "%$escaped%", pageRequest(page, size))
        return ResultPage(rows.content.map { preview(it, user) }, page, size, rows.hasNext())
    }

    fun create(email: String, r: PostRequest): PostDto {
        val user = uid(email)
        limits.checkFeature("community-write", email, 20)
        ensureCanWrite(user)
        board(r.boardId)
        val title = requiredText(r.title, 200)
        val content = requiredText(r.content, 10000)
        r.clientRequestId?.let { key ->
            posts.findByAuthorIdAndClientRequestId(user, key)?.let {
                if (
                    it.deleted ||
                        it.boardId != r.boardId ||
                        it.title != title ||
                        it.content != content
                )
                    fail(HttpStatus.CONFLICT, "재시도 키가 다른 요청에 사용되었습니다")
                return dto(it, user)
            }
        }
        return dto(
            posts.save(
                CommunityPost(
                    boardId = r.boardId,
                    authorId = user,
                    title = title,
                    content = content,
                    clientRequestId = r.clientRequestId,
                )
            ),
            user,
        )
    }

    fun get(email: String, id: Long) = dto(post(id), uid(email))

    fun update(email: String, id: Long, r: PostUpdate): PostDto {
        val user = uid(email)
        val p = lockedPost(id)
        own(p.authorId, user)
        ensureCanWrite(user)
        if (r.title == null && r.content == null) fail(HttpStatus.BAD_REQUEST, "변경할 내용을 입력해주세요")
        r.title?.let { p.title = requiredText(it, 200) }
        r.content?.let { p.content = requiredText(it, 10000) }
        p.updatedAt = Instant.now()
        return dto(p, user)
    }

    fun delete(email: String, id: Long) {
        val p = lockedPost(id)
        own(p.authorId, uid(email))
        p.deleted = true
        p.content = ""
        p.title = "삭제된 게시글"
        p.updatedAt = Instant.now()
    }

    fun comments(email: String, id: Long, page: Int, size: Int): ResultPage<CommentDto> {
        val user = uid(email)
        post(id)
        val rows =
            comments.findAllByPostIdAndDeletedFalseAndHiddenFalseOrderByCreatedAtAscIdAsc(
                id,
                pageRequest(page, size),
            )
        return ResultPage(rows.content.map { commentDto(it, user) }, page, size, rows.hasNext())
    }

    fun comment(email: String, id: Long, r: CommentRequest): CommentDto {
        val user = uid(email)
        limits.checkFeature("community-write", email, 20)
        ensureCanWrite(user)
        lockedPost(id)
        val content = requiredText(r.content, 2000)
        r.clientRequestId?.let { key ->
            comments.findByAuthorIdAndClientRequestId(user, key)?.let {
                if (it.deleted || it.postId != id || it.content != content)
                    fail(HttpStatus.CONFLICT, "재시도 키가 다른 요청에 사용되었습니다")
                return commentDto(it, user)
            }
        }
        return commentDto(
            comments.save(
                CommunityComment(
                    postId = id,
                    authorId = user,
                    content = content,
                    clientRequestId = r.clientRequestId,
                )
            ),
            user,
        )
    }

    fun updateComment(email: String, id: Long, r: CommentRequest): CommentDto {
        val c = comments.lock(id) ?: fail(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다")
        post(c.postId)
        val user = uid(email)
        own(c.authorId, user)
        ensureCanWrite(user)
        c.content = requiredText(r.content, 2000)
        c.updatedAt = Instant.now()
        return commentDto(c, user)
    }

    fun deleteComment(email: String, id: Long) {
        val c = comments.lock(id) ?: fail(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다")
        own(c.authorId, uid(email))
        c.deleted = true
        c.content = ""
        c.updatedAt = Instant.now()
    }

    fun like(email: String, id: Long, enabled: Boolean): Map<String, Any> {
        val user = uid(email)
        limits.checkFeature("community-like", email, 60)
        lockedPost(id)
        val existing = likes.findByUserIdAndPostId(user, id)
        if (enabled && existing == null)
            likes.saveAndFlush(CommunityLike(userId = user, postId = id))
        if (!enabled && existing != null) {
            likes.delete(existing)
            likes.flush()
        }
        return mapOf("likedByMe" to enabled, "likeCount" to likes.countByPostId(id))
    }

    fun report(email: String, r: ReportRequest): Map<String, Any> {
        val user = uid(email)
        limits.checkFeature("community-report", email, 10)
        // 신고 시점의 내용을 증거로 복사해 둔다. 작성자가 나중에 지우거나 고쳐도 운영자는 이 내용을 본다.
        val evidence: Triple<String?, String, Long> =
            when (r.targetType) {
                "POST" -> lockedPost(r.targetId).let { Triple(it.title, it.content, it.id!!) }
                "COMMENT" -> {
                    val c =
                        comments.lock(r.targetId) ?: fail(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다")
                    post(c.postId)
                    Triple(null, c.content, c.postId)
                }
                else -> fail(HttpStatus.BAD_REQUEST, "신고 대상은 POST 또는 COMMENT여야 합니다")
            }
        if (reports.existsByReporterIdAndTargetTypeAndTargetId(user, r.targetType, r.targetId))
            fail(HttpStatus.CONFLICT, "이미 신고한 대상입니다")
        val reason = requiredText(r.reasonCode, 50)
        if (reason !in setOf("SPAM", "ABUSE", "PRIVACY", "OTHER"))
            fail(HttpStatus.BAD_REQUEST, "신고 사유는 SPAM/ABUSE/PRIVACY/OTHER 중 선택해주세요")
        if ((r.detail?.length ?: 0) > 2000) fail(HttpStatus.BAD_REQUEST, "신고 상세는 2000자 이하여야 합니다")
        val row =
            reports.save(
                CommunityReport(
                    reporterId = user,
                    targetType = r.targetType,
                    targetId = r.targetId,
                    reasonCode = reason,
                    detail = r.detail.orEmpty().trim(),
                    snapshotTitle = evidence.first,
                    snapshotContent = evidence.second,
                    targetPostId = evidence.third,
                )
            )
        return mapOf("id" to row.id!!, "status" to "RECEIVED", "createdAt" to row.createdAt)
    }

    fun mine(email: String, page: Int, size: Int): ResultPage<PostPreview> {
        val user = uid(email)
        val rows =
            posts.findAllByAuthorIdAndDeletedFalseAndHiddenFalseOrderByCreatedAtDescIdDesc(
                user,
                pageRequest(page, size),
            )
        return ResultPage(rows.content.map { preview(it, user) }, page, size, rows.hasNext())
    }

    fun myComments(email: String, page: Int, size: Int): ResultPage<MyCommentDto> {
        val rows =
            comments.findAllByAuthorIdAndDeletedFalseAndHiddenFalseOrderByCreatedAtDescIdDesc(
                uid(email),
                pageRequest(page, size),
            )
        return ResultPage(
            rows.content.map { c ->
                MyCommentDto(
                    c.id!!,
                    c.postId,
                    posts.findById(c.postId).orElse(null)?.takeUnless { it.deleted || it.hidden }?.title,
                    c.content,
                    c.createdAt,
                )
            },
            page,
            size,
            rows.hasNext(),
        )
    }
}

@RestController
@RequestMapping("/api/community")
@Tag(name = "익명 커뮤니티")
@SecurityRequirement(name = "bearerAuth")
class CommunityController(private val s: CommunityService) {
    @GetMapping("/boards") fun boards() = s.boards()

    @GetMapping("/posts")
    fun list(
        a: Authentication,
        @RequestParam(required = false) boardId: Long?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = s.list(a.name, boardId, keyword, page, size)

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(a: Authentication, @Valid @RequestBody r: PostRequest) = s.create(a.name, r)

    @GetMapping("/posts/{postId}")
    fun get(a: Authentication, @PathVariable postId: Long) = s.get(a.name, postId)

    @PatchMapping("/posts/{postId}")
    fun patch(a: Authentication, @PathVariable postId: Long, @RequestBody r: PostUpdate) =
        s.update(a.name, postId, r)

    @DeleteMapping("/posts/{postId}")
    fun delete(a: Authentication, @PathVariable postId: Long): ResponseEntity<Void> {
        s.delete(a.name, postId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/posts/{postId}/comments")
    fun comments(
        a: Authentication,
        @PathVariable postId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = s.comments(a.name, postId, page, size)

    @PostMapping("/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun comment(
        a: Authentication,
        @PathVariable postId: Long,
        @Valid @RequestBody r: CommentRequest,
    ) = s.comment(a.name, postId, r)

    @PatchMapping("/comments/{commentId}")
    fun patchComment(
        a: Authentication,
        @PathVariable commentId: Long,
        @Valid @RequestBody r: CommentRequest,
    ) = s.updateComment(a.name, commentId, r)

    @DeleteMapping("/comments/{commentId}")
    fun deleteComment(a: Authentication, @PathVariable commentId: Long): ResponseEntity<Void> {
        s.deleteComment(a.name, commentId)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/posts/{postId}/like")
    fun like(a: Authentication, @PathVariable postId: Long) = s.like(a.name, postId, true)

    @DeleteMapping("/posts/{postId}/like")
    fun unlike(a: Authentication, @PathVariable postId: Long) = s.like(a.name, postId, false)

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun report(a: Authentication, @RequestBody r: ReportRequest) = s.report(a.name, r)

    @GetMapping("/me/posts")
    fun mine(
        a: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = s.mine(a.name, page, size)

    @GetMapping("/me/comments")
    fun myComments(
        a: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = s.myComments(a.name, page, size)
}
