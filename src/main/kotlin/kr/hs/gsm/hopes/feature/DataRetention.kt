package kr.hs.gsm.hopes.feature

import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/** 문의는 접수일, 대화는 최종 변경일부터 365일. 매일 실행하며 한 번에 100개씩 처리한다. */
@Service
class DataRetentionService(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    @Value("\${hopes.retention.inquiry-days:365}") private val inquiryDays: Long,
    @Value("\${hopes.retention.chat-days:365}") private val chatDays: Long,
) {
    init {
        require(inquiryDays > 0 && chatDays > 0)
    }

    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    fun scheduled() {
        purge(Instant.now())
    }

    fun purge(now: Instant): Map<String, Int> {
        val chatCutoff = Timestamp.from(now.minus(chatDays, ChronoUnit.DAYS))
        val inquiryCutoff = Timestamp.from(now.minus(inquiryDays, ChronoUnit.DAYS))
        var chats = 0
        var inquiries = 0
        // 작업 시간을 제한한다. 대량 적체는 다음 실행에서 이어서 정리한다.
        for (batch in 0 until 100) {
            val removed =
                tx.execute {
                    val ids =
                        jdbc.queryForList(
                            "SELECT id FROM conversations WHERE updated_at < ? ORDER BY id LIMIT 100 FOR UPDATE",
                            Long::class.java,
                            chatCutoff,
                        )
                    ids.forEach { id ->
                        jdbc.update("DELETE FROM messages WHERE conversation_id = ?", id)
                        jdbc.update("DELETE FROM conversations WHERE id = ?", id)
                    }
                    ids.size
                }!!
            chats += removed
            if (removed == 0) break
        }
        for (batch in 0 until 100) {
            val removed =
                tx.execute {
                    val ids =
                        jdbc.queryForList(
                            "SELECT id FROM inquiries WHERE created_at < ? ORDER BY id LIMIT 100 FOR UPDATE",
                            Long::class.java,
                            inquiryCutoff,
                        )
                    ids.sumOf { jdbc.update("DELETE FROM inquiries WHERE id = ?", it) }
                }!!
            inquiries += removed
            if (removed == 0) break
        }
        val verifications =
            tx.execute {
                jdbc.update(
                    "DELETE FROM email_verifications WHERE expires_at < ?",
                    Timestamp.from(now),
                )
            }!!
        val viewReceipts = tx.execute {
            val today = now.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
            jdbc.update("DELETE FROM community_post_views WHERE viewed_on < ?", java.sql.Date.valueOf(today))
        }!!
        return mapOf("chats" to chats, "inquiries" to inquiries, "verifications" to verifications, "viewReceipts" to viewReceipts)
    }
}
