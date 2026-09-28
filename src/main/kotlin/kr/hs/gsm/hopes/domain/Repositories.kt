package kr.hs.gsm.hopes.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun findByUsername(username: String): User?
    fun existsByEmail(email: String): Boolean
    fun existsByUsername(username: String): Boolean
}

interface ConversationRepository : JpaRepository<Conversation, Long> {
    fun findAllByUserIdOrderByUpdatedAtDesc(userId: Long): List<Conversation>
    fun findAllByUserIdOrderByUpdatedAtDesc(userId: Long, pageable: Pageable): Slice<Conversation>

    /** 대화 제목뿐 아니라 대화에 포함된 메시지 내용까지 검색한다(대소문자 무시). */
    @Query(
        """
        select c from Conversation c
        where c.user.id = :userId
          and (lower(c.title) like lower(concat('%', :keyword, '%'))
               or exists (
                   select m.id from ChatMessage m
                   where m.conversation = c
                     and lower(m.content) like lower(concat('%', :keyword, '%'))
               ))
        order by c.updatedAt desc
        """
    )
    fun searchByUserIdAndKeyword(@Param("userId") userId: Long, @Param("keyword") keyword: String, pageable: Pageable): Slice<Conversation>

    fun findByIdAndUserId(id: Long, userId: Long): Conversation?
    fun deleteAllByUserId(userId: Long)
}

interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findAllByConversationIdOrderByCreatedAtAscIdAsc(conversationId: Long): List<ChatMessage>
    fun findAllByConversationIdOrderByCreatedAtDescIdDesc(conversationId: Long, pageable: Pageable): Slice<ChatMessage>

    @Modifying
    @Query("delete from ChatMessage message where message.conversation.id = :conversationId")
    fun deleteAllByConversationId(@Param("conversationId") conversationId: Long): Int
}

interface EmailVerificationRepository : JpaRepository<EmailVerification, String>

interface RateLimitWindowRepository : JpaRepository<RateLimitWindow, String> {
    @Modifying
    @Query("delete from RateLimitWindow window where window.windowMinute < :minute")
    fun deleteExpiredBefore(@Param("minute") minute: Long): Int
}

interface InquiryRepository : JpaRepository<Inquiry, Long> {
    @Modifying
    @Query("delete from Inquiry inquiry where inquiry.user.id = :userId")
    fun deleteAllByUserId(@Param("userId") userId: Long): Int
}
