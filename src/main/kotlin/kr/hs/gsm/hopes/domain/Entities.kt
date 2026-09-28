package kr.hs.gsm.hopes.domain

import jakarta.persistence.*
import java.time.Instant

enum class Theme { DARK, LIGHT }
enum class MessageRole { USER, ASSISTANT }
enum class ClientPlatform { WEB, APP, UNKNOWN }

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(unique = true, nullable = false)
    var email: String = "",
    @Column(nullable = false)
    var passwordHash: String = "",
    @Column(nullable = false, unique = true, length = 50)
    var username: String = "",
    @Column(nullable = false, length = 50)
    var nickname: String = "",
    @Column(length = 50)
    var gender: String? = null,
    @Column(length = 100)
    var major: String? = null,
    var cohort: Int? = null,
    @Column(nullable = false, length = 2000)
    var profileInfo: String = "",
    @Column(length = 255)
    var profileImage: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var theme: Theme = Theme.LIGHT,
    @Column(nullable = false, length = 4000)
    var customPrompt: String = "",
    @Column(nullable = false)
    var tokenVersion: Long = 0,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "conversations")
class Conversation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    var user: User = User(),
    @Column(nullable = false, length = 255)
    var title: String = "새 대화",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
)

@Entity
@Table(name = "messages")
class ChatMessage(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    var conversation: Conversation = Conversation(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: MessageRole = MessageRole.USER,
    @Column(nullable = false, length = 12000)
    var content: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var clientPlatform: ClientPlatform = ClientPlatform.UNKNOWN,
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "inquiries")
class Inquiry(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    var user: User = User(),
    @Column(nullable = false, length = 4000)
    var content: String = "",
    @Column(nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "email_verifications")
class EmailVerification(
    @Id
    @Column(length = 255)
    var email: String = "",
    @Column(nullable = false, length = 6)
    var code: String = "",
    @Column(nullable = false)
    var expiresAt: Instant = Instant.now(),
    @Column(nullable = false)
    var verified: Boolean = false,
)

@Entity
@Table(name = "rate_limit_windows")
class RateLimitWindow(
    @Id
    @Column(name = "key_hash", length = 64)
    var keyHash: String = "",
    @Column(nullable = false)
    var windowMinute: Long = 0,
    @Column(nullable = false)
    var requestCount: Int = 0,
    @Version
    @Column(nullable = false)
    var version: Long? = null,
)
