package kr.hs.gsm.hopes.service

import kr.hs.gsm.hopes.ai.AiChatService
import kr.hs.gsm.hopes.api.*
import kr.hs.gsm.hopes.domain.*
import kr.hs.gsm.hopes.security.AccessTokenService
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Instant

@Service
class VerificationService(
    private val repository: EmailVerificationRepository,
    private val mailService: VerificationMailService,
    private val rateLimiter: RateLimiter,
    @Value("\${hopes.verification.ttl-minutes}") private val ttlMinutes: Long,
) {
    fun request(email: String, clientAddress: String) {
        requireSchoolEmail(email)
        rateLimiter.checkVerificationRequest(email, clientAddress)
        val code = "%06d".format(SecureRandom().nextInt(1_000_000))
        mailService.sendVerificationCode(email.lowercase(), code, ttlMinutes)
        repository.save(EmailVerification(email.lowercase(), code, Instant.now().plusSeconds(ttlMinutes * 60), false))
    }

    fun confirm(email: String, code: String, clientAddress: String) {
        rateLimiter.checkVerificationAttempt(email, clientAddress)
        val verification = requireValid(email, code)
        verification.verified = true
        repository.save(verification)
    }

    fun consume(email: String, code: String, clientAddress: String) {
        val existing = repository.findById(email.lowercase()).orElseThrow {
            ApiException(HttpStatus.BAD_REQUEST, "인증번호를 먼저 요청해주세요")
        }
        if (!existing.verified) rateLimiter.checkVerificationAttempt(email, clientAddress)
        val verification = requireValid(email, code)
        repository.delete(verification)
    }

    private fun requireValid(email: String, code: String): EmailVerification {
        val verification = repository.findById(email.lowercase()).orElseThrow {
            ApiException(HttpStatus.BAD_REQUEST, "인증번호를 먼저 요청해주세요")
        }
        if (verification.expiresAt.isBefore(Instant.now()) || verification.code != code) {
            throw ApiException(HttpStatus.BAD_REQUEST, "잘못된 인증 코드입니다.")
        }
        return verification
    }

    private fun requireSchoolEmail(email: String) {
        if (!email.lowercase().endsWith("@gsm.hs.kr")) {
            throw ApiException(HttpStatus.BAD_REQUEST, "학교 이메일만 사용할 수 있습니다")
        }
    }
}

@Service
class AuthService(
    private val users: UserRepository,
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
    private val inquiries: InquiryRepository,
    private val verifications: EmailVerificationRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenService: AccessTokenService,
    private val verificationService: VerificationService,
    private val rateLimiter: RateLimiter,
) {
    @Transactional
    fun signup(request: SignupRequest, clientAddress: String): TokenResponse {
        if (request.password != request.passwordConfirm) {
            throw ApiException(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다")
        }
        val email = request.email.lowercase()
        val username = request.username.trim()
        if (users.existsByEmail(email) || users.existsByUsername(username)) {
            throw ApiException(HttpStatus.CONFLICT, "이미 가입된 회원입니다")
        }
        verificationService.consume(email, request.verificationCode, clientAddress)
        val user = users.save(
            User(
                email = email,
                passwordHash = passwordEncoder.encode(request.password),
                username = username,
                nickname = username,
                gender = request.gender,
                major = request.major,
                cohort = request.cohort,
            )
        )
        return TokenResponse(tokenService.create(user.email, user.tokenVersion))
    }

    fun login(request: LoginRequest, clientAddress: String): TokenResponse {
        val key = request.username.trim()
        rateLimiter.checkLogin(key, clientAddress)
        val user = users.findByEmail(key.lowercase()) ?: users.findByUsername(key)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "등록된 회원을 찾을 수 없습니다.")
        rateLimiter.checkLoginAccount(user.email)
        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "등록된 회원을 찾을 수 없습니다.")
        }
        rateLimiter.resetLogin(user.username, user.email)
        return TokenResponse(tokenService.create(user.email, user.tokenVersion))
    }

    @Transactional
    fun logout(email: String) {
        val user = users.findByEmail(email)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다")
        user.tokenVersion += 1
    }

    @Transactional
    fun deleteAccount(email: String, request: DeleteAccountRequest) {
        val user = users.findByEmail(email)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다")
        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다")
        }

        conversations.findAllByUserIdOrderByUpdatedAtDesc(user.id!!).forEach { conversation ->
            messages.deleteAllByConversationId(conversation.id!!)
        }
        inquiries.deleteAllByUserId(user.id!!)
        conversations.deleteAllByUserId(user.id!!)
        if (verifications.existsById(user.email)) verifications.deleteById(user.email)
        rateLimiter.resetLogin(user.username, user.email)
        users.delete(user)
        users.flush()
    }

    @Transactional
    fun resetPassword(request: PasswordResetConfirmRequest, clientAddress: String) {
        verificationService.consume(request.email, request.code, clientAddress)
        val user = users.findByEmail(request.email.lowercase())
            ?: throw ApiException(HttpStatus.NOT_FOUND, "등록된 회원을 찾을 수 없습니다.")
        if (!request.newPassword.matches(Regex("^(?=.*[A-Za-z])(?=.*\\d).{8,15}$"))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "비밀번호는 영문과 숫자를 포함하여 8자 이상이어야 합니다.")
        }
        user.passwordHash = passwordEncoder.encode(request.newPassword)
        user.tokenVersion += 1
    }
}

@Service
class UserService(
    private val users: UserRepository,
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
    private val inquiries: InquiryRepository,
) {
    fun requireUser(email: String): User = users.findByEmail(email)
        ?: throw ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다")

    fun response(user: User) = UserResponse(
        user.username, user.email, user.nickname, user.profileInfo, user.profileImage,
        user.gender, user.major, user.cohort,
    )

    @Transactional
    fun update(email: String, request: MyPageUpdateRequest): UserResponse {
        val user = requireUser(email)
        request.username?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val blockedWords = listOf("관리자", "admin", "운영자")
            if (blockedWords.any { word -> it.contains(word, ignoreCase = true) }) {
                throw ApiException(HttpStatus.BAD_REQUEST, "이름에 부적절한 단어가 포함 되어 있습니다")
            }
            val duplicate = users.findByUsername(it)
            if (duplicate != null && duplicate.id != user.id) throw ApiException(HttpStatus.CONFLICT, "이미 사용 중인 이름입니다")
            user.username = it
        }
        request.nickname?.let { user.nickname = it.trim() }
        request.profileInfo?.let { user.profileInfo = it.trim() }
        request.profileImage?.let { user.profileImage = it.trim().ifEmpty { null } }
        return response(user)
    }

    @Transactional
    fun submitInquiry(email: String, content: String) {
        val user = requireUser(email)
        inquiries.save(Inquiry(user = user, content = content.trim()))
    }

    @Transactional
    fun setTheme(email: String, theme: Theme): Theme {
        requireUser(email).theme = theme
        return theme
    }

    fun settings(email: String): SettingMainResponse {
        val user = requireUser(email)
        return SettingMainResponse(response(user), user.theme, user.customPrompt)
    }

    @Transactional
    fun updateSettings(email: String, request: SettingUpdateRequest): SettingMainResponse {
        val user = requireUser(email)
        request.customPrompt?.let { user.customPrompt = it.trim() }
        if (request.deleteAllChats) {
            conversations.findAllByUserIdOrderByUpdatedAtDesc(user.id!!).forEach { conversation ->
                messages.deleteAllByConversationId(conversation.id!!)
            }
            conversations.deleteAllByUserId(user.id!!)
        }
        return SettingMainResponse(response(user), user.theme, user.customPrompt)
    }
}

@Service
class ChatService(
    private val users: UserService,
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
    private val ai: AiChatService,
    private val transactionTemplate: TransactionTemplate,
    private val rateLimiter: RateLimiter,
) {
    fun main(email: String, keyword: String?, page: Int, size: Int): MainResponse {
        val user = users.requireUser(email)
        if (keyword != null && keyword.length > CHAT_TITLE_MAX_LENGTH) {
            throw ApiException(HttpStatus.BAD_REQUEST, "검색어는 ${CHAT_TITLE_MAX_LENGTH}자 이하여야 합니다")
        }
        val pageable = pageRequest(page, size)
        val chats = if (keyword.isNullOrBlank()) {
            conversations.findAllByUserIdOrderByUpdatedAtDesc(user.id!!, pageable)
        } else {
            conversations.searchByUserIdAndKeyword(user.id!!, keyword.trim(), pageable)
        }
        return MainResponse(chats.content.map(::summary), true, keyword, page, size, chats.hasNext())
    }

    @Transactional
    fun create(email: String, request: CreateChatRequest): ChatResponse {
        val user = users.requireUser(email)
        val conversation = conversations.save(Conversation(user = user, title = request.title?.trim()?.takeIf { it.isNotEmpty() } ?: "새 대화"))
        return detail(conversation)
    }

    fun get(email: String, id: Long, messagePage: Int, messageSize: Int): ChatResponse =
        detail(requireConversation(users.requireUser(email), id), messagePage, messageSize)

    @Transactional
    fun delete(email: String, id: Long) {
        val conversation = requireConversation(users.requireUser(email), id)
        messages.deleteAllByConversationId(conversation.id!!)
        conversations.delete(conversation)
    }

    fun send(email: String, id: Long, request: SendMessageRequest): ChatResponse {
        rateLimiter.checkMessage(email)
        val user = users.requireUser(email)
        val conversation = requireConversation(user, id)
        if (ai.enabled && !ai.isReady()) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI가 아직 준비 중입니다. 잠시 후 다시 시도해주세요")
        }
        val content = request.content.trim()
        // 전체 대화를 메모리에 올리지 않고 Gemini에 전달할 최근 내역만 조회한다.
        val history = if (ai.enabled && ai.historyLimit > 0) {
            messages.findAllByConversationIdOrderByCreatedAtDescIdDesc(
                conversation.id!!,
                PageRequest.of(0, ai.historyLimit.coerceAtMost(MAX_PAGE_SIZE)),
            ).content.asReversed()
        } else {
            emptyList()
        }
        // Gemini 호출은 최대 60초까지 걸릴 수 있어 트랜잭션(DB 커넥션) 밖에서 실행한다.
        // 실패하면 여기서 예외가 전파되어 아무것도 저장되지 않으므로 클라이언트는 같은 내용으로 재시도하면 된다.
        val answer = if (ai.enabled) ai.reply(user, history, content) else null
        transactionTemplate.executeWithoutResult {
            messages.save(ChatMessage(conversation = conversation, role = MessageRole.USER, content = content, createdAt = Instant.now()))
            if (conversation.title == "새 대화") conversation.title = content.take(40)
            answer?.let {
                messages.save(ChatMessage(conversation = conversation, role = MessageRole.ASSISTANT, content = it.take(12000), createdAt = Instant.now()))
            }
            conversation.updatedAt = Instant.now()
            conversations.save(conversation)
        }
        return detail(conversation)
    }

    private fun requireConversation(user: User, id: Long): Conversation =
        conversations.findByIdAndUserId(id, user.id!!)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "지난 대화를 찾을 수 없습니다")

    private fun summary(value: Conversation) = ChatSummary(value.id!!, value.title, value.updatedAt)

    private fun detail(value: Conversation, page: Int = 0, size: Int = DEFAULT_PAGE_SIZE): ChatResponse {
        val result = messages.findAllByConversationIdOrderByCreatedAtDescIdDesc(value.id!!, pageRequest(page, size))
        return ChatResponse(
            value.id!!,
            value.title,
            result.content.asReversed().map { MessageResponse(it.id!!, it.role, it.content, it.createdAt) },
            page,
            size,
            result.hasNext(),
        )
    }

    private fun pageRequest(page: Int, size: Int): PageRequest {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) {
            throw ApiException(HttpStatus.BAD_REQUEST, "페이지는 0 이상, 크기는 1~$MAX_PAGE_SIZE 사이여야 합니다")
        }
        return PageRequest.of(page, size)
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_PAGE_SIZE = 100
    }
}
