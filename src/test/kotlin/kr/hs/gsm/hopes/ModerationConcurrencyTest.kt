package kr.hs.gsm.hopes

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.hs.gsm.hopes.api.ApiException
import kr.hs.gsm.hopes.domain.*
import kr.hs.gsm.hopes.feature.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(
    properties =
        [
            "hopes.ai.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:moderation-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        ]
)
class ModerationConcurrencyTest
@Autowired
constructor(
    private val users: UserRepository,
    private val community: CommunityService,
    private val moderation: ModerationService,
    private val posts: PostRepository,
    private val comments: CommentRepository,
    private val actions: ModerationActionRepository,
    private val manager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
) {
    private fun account(role: UserRole = UserRole.USER): User {
        val key = UUID.randomUUID().toString()
        return users.save(
            User(
                email = "$key@gsm.hs.kr",
                username = key,
                nickname = key,
                passwordHash = "unused",
                role = role,
            )
        )
    }

    private fun post(author: User): Long {
        val board = community.boards().getValue("items").first { it.cohort == null }.id
        return community.create(author.email, PostRequest(board, "before", "before")).id
    }

    private fun report(target: String, id: Long): Long =
        community.report(account().email, ReportRequest(target, id, "OTHER"))["id"] as Long

    @Test
    fun `concurrent detail reads increment one daily view and next day can count again`() {
        val author = account()
        val viewer = account()
        val id = post(author)
        val executor = Executors.newFixedThreadPool(6)
        try {
            val futures = (1..12).map { executor.submit<Long> { community.get(viewer.email, id).viewCount } }
            futures.forEach { assertEquals(1L, it.get(15, TimeUnit.SECONDS)) }
            assertEquals(1L, posts.findById(id).get().viewCount)
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM community_post_views WHERE post_id=?", Int::class.java, id))
            jdbc.update("UPDATE community_post_views SET viewed_on=? WHERE post_id=?", java.sql.Date.valueOf(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1)), id)
            assertEquals(2L, community.get(viewer.email, id).viewCount)
            assertEquals(2L, community.get(author.email, id).viewCount)
            jdbc.update("UPDATE community_posts SET hidden=TRUE WHERE id=?", id)
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException::class.java) { community.get(viewer.email, id) }.status)
        } finally { executor.shutdownNow() }
    }

    // Observe an actual DB lock wait rather than relying on arbitrary timing.
    private fun awaitBlockedTransaction() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_STATE = 'BLOCKED'",
                    Int::class.java,
                )!! > 0
            )
                return
            Thread.sleep(10)
        }
        fail<Unit>("The concurrent transaction never reached a database lock wait")
    }

    @Test
    fun `concurrent close checks the committed status and records only one action`() {
        val id = report("POST", post(account()))
        val admin = account(UserRole.ADMIN)
        val otherAdmin = account(UserRole.ADMIN)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val second =
                TransactionTemplate(manager).execute {
                    moderation.act(admin.email, id, ModerationRequest("RESOLVE"))
                    val future =
                        executor.submit<HttpStatus> {
                            try {
                                moderation.act(otherAdmin.email, id, ModerationRequest("DISMISS"))
                                HttpStatus.OK
                            } catch (e: ApiException) {
                                e.status
                            }
                        }
                    awaitBlockedTransaction()
                    future
                }!!
            assertEquals(HttpStatus.CONFLICT, second.get(10, TimeUnit.SECONDS))
            assertEquals(ReportStatus.RESOLVED, moderation.detail(admin.email, id).status)
            assertEquals(
                listOf(ModerationAction.RESOLVE),
                actions.findAllByReportIdOrderByCreatedAtAscIdAsc(id).map { it.action },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `hiding a post preserves a concurrent committed author edit`() = verifyHide(false)

    @Test fun `hiding a comment preserves a concurrent committed author edit`() = verifyHide(true)

    private fun verifyHide(comment: Boolean) {
        val author = account()
        val postId = post(author)
        val targetId =
            if (comment) community.comment(author.email, postId, CommentRequest("before")).id
            else postId
        val reportId = report(if (comment) "COMMENT" else "POST", targetId)
        val admin = account(UserRole.ADMIN)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val hide =
                TransactionTemplate(manager).execute {
                    if (comment) {
                        comments.lock(targetId)!!.content = "after edit"
                        comments.flush()
                    } else {
                        posts.lock(targetId)!!.content = "after edit"
                        posts.flush()
                    }
                    val future =
                        executor.submit<ReportDetailDto> {
                            moderation.act(admin.email, reportId, ModerationRequest("HIDE"))
                        }
                    awaitBlockedTransaction()
                    future
                }!!
            val result = hide.get(10, TimeUnit.SECONDS)
            assertTrue(result.currentTarget.hidden)
            assertEquals("after edit", result.currentTarget.content)
            val persisted = moderation.detail(admin.email, reportId).currentTarget
            assertTrue(persisted.hidden)
            assertEquals("after edit", persisted.content)
        } finally {
            executor.shutdownNow()
        }
    }
}
