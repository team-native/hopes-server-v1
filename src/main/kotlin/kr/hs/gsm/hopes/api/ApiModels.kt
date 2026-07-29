package kr.hs.gsm.hopes.api

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import io.swagger.v3.oas.annotations.media.Schema
import kr.hs.gsm.hopes.domain.MessageRole
import kr.hs.gsm.hopes.domain.Theme
import java.time.Instant

const val EMAIL_MAX_LENGTH = 255
const val USERNAME_MAX_LENGTH = 50
const val NICKNAME_MAX_LENGTH = 50
const val PROFILE_INFO_MAX_LENGTH = 2000
const val PROFILE_IMAGE_MAX_LENGTH = 255
const val CUSTOM_PROMPT_MAX_LENGTH = 4000
const val CHAT_TITLE_MAX_LENGTH = 255
const val MESSAGE_MAX_LENGTH = 12000

data class SignupRequest(
    @field:Email @field:Size(max = EMAIL_MAX_LENGTH)
    @field:Pattern(regexp = "^[A-Za-z0-9._%+-]+@gsm\\.hs\\.kr$", message = "학교 이메일만 사용할 수 있습니다")
    val email: String,
    @field:NotBlank @field:Size(max = USERNAME_MAX_LENGTH) val username: String,
    @field:Size(min = 8, max = 15) @field:Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호는 영문과 숫자를 포함해야 합니다")
    val password: String,
    @field:NotBlank @field:Size(min = 8, max = 15) val passwordConfirm: String,
    @field:Pattern(regexp = "^\\d{6}$", message = "인증번호는 숫자 6자리여야 합니다")
    @field:Schema(description = "이메일 인증 확인에 사용한 숫자 6자리 코드", example = "123456", pattern = "^\\d{6}$")
    val verificationCode: String,
    @field:Size(max = 50) val gender: String? = null,
    @field:Size(max = 100) val major: String? = null,
    @field:Min(1) @field:Max(100) val cohort: Int? = null,
)

data class LoginRequest(
    @field:NotBlank @field:Size(max = EMAIL_MAX_LENGTH) val username: String,
    @field:NotBlank @field:Size(max = 255) val password: String,
)
data class EmailVerificationRequest(
    @field:Email @field:Size(max = EMAIL_MAX_LENGTH)
    @field:Schema(description = "인증번호를 받을 학교 이메일", example = "s12345@gsm.hs.kr")
    val email: String,
)
data class EmailVerificationConfirmRequest(
    @field:Email @field:Size(max = EMAIL_MAX_LENGTH) val email: String,
    @field:Pattern(regexp = "^\\d{6}$", message = "인증번호는 숫자 6자리여야 합니다")
    @field:Schema(description = "이메일로 발송된 숫자 6자리 코드", example = "123456", pattern = "^\\d{6}$")
    val code: String,
)
data class PasswordResetRequest(
    @field:Email @field:Size(max = EMAIL_MAX_LENGTH)
    @field:Schema(description = "비밀번호 재설정 인증번호를 받을 학교 이메일", example = "s12345@gsm.hs.kr")
    val email: String,
)
data class PasswordResetConfirmRequest(
    @field:Email @field:Size(max = EMAIL_MAX_LENGTH) val email: String,
    @field:Pattern(regexp = "^\\d{6}$", message = "인증번호는 숫자 6자리여야 합니다")
    @field:Schema(description = "비밀번호 재설정용 숫자 6자리 코드", example = "123456", pattern = "^\\d{6}$")
    val code: String,
    @field:Size(min = 8, max = 15)
    @field:Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "비밀번호는 영문과 숫자를 포함해야 합니다")
    val newPassword: String,
)
data class ThemeRequest(val theme: Theme)
data class MyPageUpdateRequest(
    @field:Size(max = USERNAME_MAX_LENGTH) val username: String? = null,
    @field:Size(max = NICKNAME_MAX_LENGTH) val nickname: String? = null,
    @field:Size(max = PROFILE_INFO_MAX_LENGTH) val profileInfo: String? = null,
    @field:Size(max = PROFILE_IMAGE_MAX_LENGTH) val profileImage: String? = null,
)
data class SettingUpdateRequest(
    @field:Size(max = CUSTOM_PROMPT_MAX_LENGTH) val customPrompt: String? = null,
    val deleteAllChats: Boolean = false,
)
data class CreateChatRequest(@field:Size(max = CHAT_TITLE_MAX_LENGTH) val title: String? = null)
data class SendMessageRequest(
    @field:NotBlank @field:Size(max = MESSAGE_MAX_LENGTH, message = "질문은 12,000자 이하여야 합니다") val content: String,
)
data class InquiryRequest(@field:NotBlank @field:Size(max = CUSTOM_PROMPT_MAX_LENGTH) val content: String)

data class TokenResponse(val accessToken: String, val tokenType: String = "Bearer")
data class UserResponse(val username: String, val email: String, val nickname: String, val profileInfo: String, val profileImage: String?, val gender: String?, val major: String?, val cohort: Int?)
data class ChatSummary(val id: Long, val title: String, val updatedAt: Instant)
data class MessageResponse(val id: Long, val role: MessageRole, val content: String, val createdAt: Instant)
data class ChatResponse(
    val id: Long,
    val title: String,
    val messages: List<MessageResponse>,
    val messagePage: Int = 0,
    val messageSize: Int = 50,
    val hasMoreMessages: Boolean = false,
)
data class MainResponse(
    val chatList: List<ChatSummary>,
    val newChat: Boolean = true,
    val searchKeyword: String? = null,
    val page: Int = 0,
    val size: Int = 50,
    val hasNext: Boolean = false,
)
data class SettingMainResponse(val accountSetting: UserResponse, val theme: Theme, val customPrompt: String, val logout: Boolean = true, val inquiry: Boolean = true)
data class MessageEnvelope(val message: String)
