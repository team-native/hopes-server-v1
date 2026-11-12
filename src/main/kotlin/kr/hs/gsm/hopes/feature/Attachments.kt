package kr.hs.gsm.hopes.feature

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kr.hs.gsm.hopes.ai.AiAttachment
import kr.hs.gsm.hopes.service.RateLimiter
import kr.hs.gsm.hopes.service.UserService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.http.*
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@Entity
@Table(
    name = "chat_attachments",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "client_request_id"])],
)
class ChatAttachment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(nullable = false) var userId: Long = 0,
    var messageId: Long? = null,
    @Column(nullable = false, length = 255) var fileName: String = "",
    @Column(nullable = false, length = 100) var mimeType: String = "",
    @Column(nullable = false) var sizeBytes: Long = 0,
    @Column(nullable = false, unique = true, length = 36) var storageKey: String = "",
    @Column(nullable = false, length = 64) var digest: String = "",
    @Column(length = 100) var clientRequestId: String? = null,
    @Column(nullable = false) var expiresAt: Instant = Instant.now(),
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
)

interface AttachmentRepository : JpaRepository<ChatAttachment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :uid")
    fun lockOwner(@Param("uid") uid: Long): kr.hs.gsm.hopes.domain.User?

    fun findByIdAndUserId(id: Long, userId: Long): ChatAttachment?

    fun findByUserIdAndClientRequestId(userId: Long, key: String): ChatAttachment?

    fun findAllByMessageId(messageId: Long): List<ChatAttachment>

    fun existsByStorageKey(storageKey: String): Boolean

    fun findByStorageKey(storageKey: String): ChatAttachment?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ChatAttachment a where a.id = :id and a.userId = :uid")
    fun lock(@Param("id") id: Long, @Param("uid") uid: Long): ChatAttachment?

    @Query(
        "select coalesce(sum(a.sizeBytes),0) from ChatAttachment a where a.userId = :uid and a.expiresAt > :now"
    )
    fun usage(@Param("uid") uid: Long, @Param("now") now: Instant): Long
}

data class AttachmentDto(
    val id: Long,
    val type: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: String,
    val previewUrl: String?,
    val expiresAt: Instant,
)

data class PreparedAttachments(val rows: List<ChatAttachment>, val media: List<AiAttachment>)

@Configuration @EnableScheduling class AttachmentScheduling

@Service
class AttachmentService(
    private val users: UserService,
    private val repository: AttachmentRepository,
    private val limits: RateLimiter,
    private val tx: TransactionTemplate,
    @Value("\${hopes.attachments.directory:./data/attachments}") directory: String,
    @Value("\${hopes.attachments.max-file-bytes:5242880}") private val maxFile: Long,
    @Value("\${hopes.attachments.max-count:3}") private val maxCount: Int,
    @Value("\${hopes.attachments.max-total-bytes:12582912}") private val maxTotal: Long,
    @Value("\${hopes.attachments.max-pdf-pages:50}") private val maxPages: Int,
    @Value("\${hopes.attachments.retention-hours:720}") private val retentionHours: Long,
    @Value("\${hopes.attachments.user-quota-bytes:104857600}") private val quota: Long,
) {
    private val root = Path.of(directory).toAbsolutePath().normalize()

    init {
        require(
            maxFile > 0 &&
                maxCount > 0 &&
                maxTotal > 0 &&
                maxPages > 0 &&
                retentionHours > 0 &&
                quota > 0
        )
        Files.createDirectories(root)
    }

    private fun path(key: String): Path {
        if (!key.matches(Regex("[a-f0-9-]{36}"))) error("Invalid storage key")
        return root.resolve(key)
    }

    private fun owned(email: String, id: Long) =
        repository.findByIdAndUserId(id, users.requireUser(email).id!!)
            ?: fail(HttpStatus.NOT_FOUND, "첨부파일을 찾을 수 없습니다")

    fun dto(row: ChatAttachment): AttachmentDto {
        val ready =
            row.expiresAt.isAfter(Instant.now()) && Files.isRegularFile(path(row.storageKey))
        return AttachmentDto(
            row.id!!,
            if (row.mimeType == "application/pdf") "PDF" else "IMAGE",
            row.fileName,
            row.mimeType,
            row.sizeBytes,
            if (ready) "READY" else "EXPIRED",
            if (ready) "/api/attachments/${row.id}/content" else null,
            row.expiresAt,
        )
    }

    fun metadata(email: String, id: Long) = dto(owned(email, id))

    private fun bytes(row: ChatAttachment): ByteArray {
        if (!row.expiresAt.isAfter(Instant.now()) || !Files.isRegularFile(path(row.storageKey)))
            fail(HttpStatus.GONE, "첨부파일 보관기간이 만료되었습니다")
        return Files.readAllBytes(path(row.storageKey))
    }

    fun content(email: String, id: Long): Pair<ChatAttachment, ByteArray> {
        val row = owned(email, id)
        return row to bytes(row)
    }

    fun upload(email: String, file: MultipartFile, key: String?): AttachmentDto {
        val uid = users.requireUser(email).id!!
        limits.checkFeature("attachment-upload", email, 10)
        if (file.isEmpty) fail(HttpStatus.BAD_REQUEST, "빈 파일은 업로드할 수 없습니다")
        if (file.size > maxFile) fail(HttpStatus.PAYLOAD_TOO_LARGE, "파일은 1~${maxFile}바이트여야 합니다")
        if (key != null && (key.isBlank() || key.length > 100))
            fail(HttpStatus.BAD_REQUEST, "재시도 키를 확인해주세요")
        val name =
            file.originalFilename
                .orEmpty()
                .replace('\\', '/')
                .substringAfterLast('/')
                .filter { !it.isISOControl() }
                .take(255)
        if (name.isBlank()) fail(HttpStatus.BAD_REQUEST, "파일 이름이 필요합니다")
        val data = file.bytes
        val mime = validate(data, name, file.contentType)
        val digest =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        key?.let {
            repository.findByUserIdAndClientRequestId(uid, it)?.let { old ->
                if (old.digest != digest || old.fileName != name)
                    fail(HttpStatus.CONFLICT, "재시도 키가 다른 파일에 사용되었습니다")
                return dto(old)
            }
        }
        if (repository.usage(uid, Instant.now()) + data.size > quota)
            fail(HttpStatus.PAYLOAD_TOO_LARGE, "첨부파일 저장 한도를 초과했습니다")
        val storage = UUID.randomUUID().toString()
        val temp = Files.createTempFile(root, "upload-", ".tmp")
        try {
            Files.write(temp, data)
            Files.move(temp, path(storage), StandardCopyOption.ATOMIC_MOVE)
            return tx.execute {
                repository.lockOwner(uid) ?: fail(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다")
                if (repository.usage(uid, Instant.now()) + data.size > quota)
                    fail(HttpStatus.PAYLOAD_TOO_LARGE, "첨부파일 저장 한도를 초과했습니다")
                val row =
                    repository.saveAndFlush(
                        ChatAttachment(
                            userId = uid,
                            fileName = name,
                            mimeType = mime,
                            sizeBytes = data.size.toLong(),
                            storageKey = storage,
                            digest = digest,
                            clientRequestId = key,
                            expiresAt = Instant.now().plusSeconds(retentionHours * 3600),
                        )
                    )
                dto(row)
            }!!
        } catch (e: Exception) {
            Files.deleteIfExists(path(storage))
            throw e
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun validate(data: ByteArray, name: String, declared: String?): String {
        val extension = name.substringAfterLast('.').lowercase()
        val mime =
            when (extension) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg",
                "jpeg" -> "image/jpeg"
                else -> fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PNG/JPEG/PDF만 지원합니다")
            }
        if (declared != null && declared != mime && declared != "application/octet-stream")
            fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "파일 형식과 MIME이 일치하지 않습니다")
        try {
            if (mime == "application/pdf") {
                if (!data.take(5).toByteArray().contentEquals("%PDF-".toByteArray()))
                    fail(HttpStatus.BAD_REQUEST, "손상된 PDF입니다")
                Loader.loadPDF(data).use {
                    if (it.isEncrypted || it.numberOfPages !in 1..maxPages)
                        fail(HttpStatus.BAD_REQUEST, "암호화되었거나 페이지 제한을 초과한 PDF입니다")
                }
            } else
                ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    if (!readers.hasNext()) fail(HttpStatus.BAD_REQUEST, "손상된 이미지입니다")
                    val reader = readers.next()
                    try {
                        reader.input = input
                        val expected = if (mime == "image/png") "png" else "JPEG"
                        if (!reader.formatName.equals(expected, true))
                            fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "이미지 내용과 확장자가 다릅니다")
                        if (reader.getWidth(0).toLong() * reader.getHeight(0) > 25_000_000)
                            fail(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 해상도 제한을 초과했습니다")
                        reader.read(0) ?: fail(HttpStatus.BAD_REQUEST, "손상된 이미지입니다")
                    } finally {
                        reader.dispose()
                    }
                }
        } catch (e: kr.hs.gsm.hopes.api.ApiException) {
            throw e
        } catch (e: InvalidPasswordException) {
            fail(HttpStatus.BAD_REQUEST, "암호화된 PDF는 지원하지 않습니다")
        } catch (e: Exception) {
            fail(HttpStatus.BAD_REQUEST, "파일을 읽을 수 없습니다")
        }
        return mime
    }

    fun prepare(uid: Long, ids: List<Long>): PreparedAttachments {
        if (ids.size > maxCount || ids.distinct().size != ids.size)
            fail(HttpStatus.BAD_REQUEST, "첨부 개수 또는 중복 ID를 확인해주세요")
        val rows =
            ids.map {
                repository.findByIdAndUserId(it, uid)
                    ?: fail(HttpStatus.NOT_FOUND, "첨부파일을 찾을 수 없습니다")
            }
        if (rows.any { it.messageId != null }) fail(HttpStatus.CONFLICT, "이미 메시지에 연결된 첨부파일입니다")
        if (rows.sumOf { it.sizeBytes } > maxTotal)
            fail(HttpStatus.PAYLOAD_TOO_LARGE, "전체 첨부 용량 제한을 초과했습니다")
        return PreparedAttachments(rows, rows.map { AiAttachment(it.mimeType, bytes(it)) })
    }

    /**
     * Called within the message save transaction: a concurrent send/delete cannot claim the same
     * file.
     */
    fun link(uid: Long, ids: List<Long>, messageId: Long) {
        ids.sorted().forEach { id ->
            val row = repository.lock(id, uid) ?: fail(HttpStatus.NOT_FOUND, "첨부파일을 찾을 수 없습니다")
            if (row.messageId != null) fail(HttpStatus.CONFLICT, "이미 전송된 첨부파일입니다")
            if (!row.expiresAt.isAfter(Instant.now())) fail(HttpStatus.GONE, "첨부파일이 만료되었습니다")
            row.messageId = messageId
        }
    }

    fun forMessage(id: Long) = repository.findAllByMessageId(id).map(::dto)

    fun historyMedia(messageIds: List<Long>, current: PreparedAttachments): List<AiAttachment> {
        var budget = maxTotal - current.rows.sumOf { it.sizeBytes }
        var remaining = maxCount - current.rows.size
        val media = mutableListOf<AiAttachment>()
        for (id in messageIds.asReversed()) for (row in repository.findAllByMessageId(id)) {
            if (
                remaining > 0 &&
                    row.sizeBytes <= budget &&
                    row.expiresAt.isAfter(Instant.now()) &&
                    Files.isRegularFile(path(row.storageKey))
            ) {
                media += AiAttachment(row.mimeType, bytes(row), "이전 대화의 첨부파일")
                remaining--
                budget -= row.sizeBytes
            }
        }
        return media.asReversed() + current.media
    }

    fun delete(email: String, id: Long) {
        val uid = users.requireUser(email).id!!
        val key =
            tx.execute {
                val row = repository.lock(id, uid) ?: fail(HttpStatus.NOT_FOUND, "첨부파일을 찾을 수 없습니다")
                if (row.messageId != null) fail(HttpStatus.CONFLICT, "전송된 첨부파일은 개별 삭제할 수 없습니다")
                repository.delete(row)
                row.storageKey
            }!!
        Files.deleteIfExists(path(key))
    }

    @Scheduled(fixedDelayString = "\${hopes.attachments.cleanup-ms:3600000}")
    fun cleanup() {
        // Cascading account/chat deletion removes metadata; clean unreferenced files after an
        // upload grace period.
        Files.list(root).use { files ->
            files
                .filter {
                    Files.isRegularFile(it) &&
                        Files.getLastModifiedTime(it)
                            .toInstant()
                            .isBefore(Instant.now().minusSeconds(3600))
                }
                .forEach {
                    val key = it.fileName.toString()
                    val managed = key.matches(Regex("[a-f0-9-]{36}"))
                    val row = if (managed) repository.findByStorageKey(key) else null
                    if (
                        (managed && (row == null || row.expiresAt.isBefore(Instant.now()))) ||
                            (key.startsWith("upload-") && key.endsWith(".tmp"))
                    )
                        Files.deleteIfExists(it)
                }
        }
    }
}

@RestController
@RequestMapping("/api/attachments")
@Tag(name = "멀티모달 첨부")
@SecurityRequirement(name = "bearerAuth")
class AttachmentController(private val service: AttachmentService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        a: Authentication,
        @RequestPart file: MultipartFile,
        @RequestParam(required = false) clientRequestId: String?,
    ) = service.upload(a.name, file, clientRequestId)

    @GetMapping("/{attachmentId}")
    fun metadata(a: Authentication, @PathVariable attachmentId: Long) =
        service.metadata(a.name, attachmentId)

    @GetMapping("/{attachmentId}/content")
    fun content(a: Authentication, @PathVariable attachmentId: Long): ResponseEntity<ByteArray> {
        val (row, data) = service.content(a.name, attachmentId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(row.mimeType))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment()
                    .filename(row.fileName, StandardCharsets.UTF_8)
                    .build()
                    .toString(),
            )
            .body(data)
    }

    @DeleteMapping("/{attachmentId}")
    fun delete(a: Authentication, @PathVariable attachmentId: Long): ResponseEntity<Void> {
        service.delete(a.name, attachmentId)
        return ResponseEntity.noContent().build()
    }
}
