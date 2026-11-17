package kr.hs.gsm.hopes

import java.time.Instant
import java.time.temporal.ChronoUnit
import kr.hs.gsm.hopes.domain.*
import kr.hs.gsm.hopes.feature.DataRetentionService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    properties =
        [
            "hopes.mail.enabled=false",
            "hopes.ai.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:retention-ops;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        ]
)
class DataRetentionOperationsTest
@Autowired
constructor(
    val users: UserRepository,
    val inquiries: InquiryRepository,
    val chats: ConversationRepository,
    val messages: ChatMessageRepository,
    val retention: DataRetentionService,
) {
    @Test
    fun `retention removes old records and messages but preserves recent ones`() {
        val owner =
            users.saveAndFlush(
                User(email = "retention@example.invalid", username = "retention", nickname = "QA")
            )
        val now = Instant.now()
        val old = now.minus(366, ChronoUnit.DAYS)
        val expired =
            chats.saveAndFlush(
                Conversation(user = owner, title = "expired", createdAt = old, updatedAt = old)
            )
        val recent = chats.saveAndFlush(Conversation(user = owner, title = "recent"))
        messages.saveAndFlush(ChatMessage(conversation = expired, content = "old"))
        val inquiry =
            inquiries.saveAndFlush(Inquiry(user = owner, content = "old", createdAt = old))
        val keep = inquiries.saveAndFlush(Inquiry(user = owner, content = "recent"))
        retention.purge(now)
        assertFalse(chats.existsById(expired.id!!))
        assertTrue(chats.existsById(recent.id!!))
        assertEquals(0, messages.findAllByConversationIdOrderByCreatedAtAscIdAsc(expired.id!!).size)
        assertFalse(inquiries.existsById(inquiry.id!!))
        assertTrue(inquiries.existsById(keep.id!!))
    }
}
