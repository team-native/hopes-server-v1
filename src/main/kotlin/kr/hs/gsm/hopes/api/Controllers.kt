package kr.hs.gsm.hopes.api

import jakarta.validation.Valid
import jakarta.servlet.http.HttpServletRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import kr.hs.gsm.hopes.service.AuthService
import kr.hs.gsm.hopes.service.ChatService
import kr.hs.gsm.hopes.service.UserService
import kr.hs.gsm.hopes.service.VerificationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
@Tag(name = "인증", description = "이메일 인증, 회원가입, 로그인, 비밀번호 재설정, 로그아웃")
class AuthController(
    private val authService: AuthService,
    private val verificationService: VerificationService,
) {
    @PostMapping("/signup/email-verifications")
    @Operation(
        summary = "회원가입 인증번호 발송",
        description = "@gsm.hs.kr 학교 이메일로 숫자 6자리 인증번호를 발송합니다. 운영 환경에서 인증번호는 10분 동안 유효하며, 새 번호를 요청하면 이전 번호는 사용할 수 없습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "인증번호 발송 접수"),
            ApiResponse(responseCode = "400", description = "이메일 형식 또는 학교 이메일 검증 실패"),
            ApiResponse(responseCode = "429", description = "이메일·IP 기준 요청 횟수 초과"),
            ApiResponse(responseCode = "502", description = "인증 메일 발송 실패"),
        ]
    )
    fun requestVerification(
        @Valid @RequestBody request: EmailVerificationRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<MessageEnvelope> {
        verificationService.request(request.email, httpRequest.remoteAddr)
        return ResponseEntity.accepted().body(MessageEnvelope("인증번호를 발송했습니다"))
    }

    @PostMapping("/signup/email-verifications/confirm")
    @Operation(
        summary = "회원가입 이메일 인증 확인",
        description = "이메일과 숫자 6자리 인증번호를 확인해 인증 완료 상태로 변경합니다. 확인 후 회원가입 요청에도 같은 인증번호를 포함해야 하며, 회원가입이 완료되면 인증번호는 폐기됩니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "이메일 인증 완료"),
            ApiResponse(responseCode = "400", description = "인증번호 미요청, 불일치 또는 만료"),
            ApiResponse(responseCode = "429", description = "이메일·IP 기준 확인 시도 횟수 초과"),
        ]
    )
    fun confirmVerification(
        @Valid @RequestBody request: EmailVerificationConfirmRequest,
        httpRequest: HttpServletRequest,
    ) = MessageEnvelope("이메일 인증이 완료되었습니다").also {
        verificationService.confirm(request.email, request.code, httpRequest.remoteAddr)
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "회원가입",
        description = "confirm 단계에서 확인한 이메일과 인증번호를 verificationCode에 다시 전달합니다. 성공하면 인증번호는 즉시 폐기되어 재사용할 수 없습니다.",
    )
    fun signup(@Valid @RequestBody request: SignupRequest, httpRequest: HttpServletRequest) =
        authService.signup(request, httpRequest.remoteAddr)

    @PostMapping("/login")
    @Operation(summary = "로그인")
    fun login(@Valid @RequestBody request: LoginRequest, httpRequest: HttpServletRequest) =
        authService.login(request, httpRequest.remoteAddr)

    @PostMapping("/password/request")
    @Operation(
        summary = "비밀번호 재설정 인증번호 발송",
        description = "비밀번호 재설정에 사용할 숫자 6자리 인증번호를 학교 이메일로 발송합니다. 운영 환경에서 인증번호는 10분 동안 유효합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "인증번호 발송 접수"),
            ApiResponse(responseCode = "400", description = "이메일 형식 또는 학교 이메일 검증 실패"),
            ApiResponse(responseCode = "429", description = "이메일·IP 기준 요청 횟수 초과"),
            ApiResponse(responseCode = "502", description = "인증 메일 발송 실패"),
        ]
    )
    fun requestPasswordReset(
        @Valid @RequestBody request: PasswordResetRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<MessageEnvelope> {
        verificationService.request(request.email, httpRequest.remoteAddr)
        return ResponseEntity.accepted().body(MessageEnvelope("비밀번호 변경 인증번호를 발송했습니다"))
    }

    @PostMapping("/password/reset")
    @Operation(
        summary = "비밀번호 재설정",
        description = "이메일 인증번호를 확인하면서 새 비밀번호로 변경합니다. 별도 confirm 요청은 필요하지 않으며, 성공하면 인증번호와 기존 액세스 토큰이 모두 무효화됩니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "비밀번호 변경 완료"),
            ApiResponse(responseCode = "400", description = "인증번호 오류·만료 또는 비밀번호 정책 위반"),
            ApiResponse(responseCode = "404", description = "등록된 계정 없음"),
            ApiResponse(responseCode = "429", description = "이메일·IP 기준 확인 시도 횟수 초과"),
        ]
    )
    fun resetPassword(
        @Valid @RequestBody request: PasswordResetConfirmRequest,
        httpRequest: HttpServletRequest,
    ) = MessageEnvelope("비밀번호가 변경되었습니다").also {
        authService.resetPassword(request, httpRequest.remoteAddr)
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃", security = [SecurityRequirement(name = "bearerAuth")])
    fun logout(authentication: Authentication) = MessageEnvelope("로그아웃되었습니다").also {
        authService.logout(authentication.name)
    }
}

@RestController
@RequestMapping("/api")
@Tag(name = "채팅", description = "대화 목록, 대화 생성, 메시지 조회와 AI 답변")
@SecurityRequirement(name = "bearerAuth")
class MainController(private val chats: ChatService) {
    @GetMapping("/main")
    @Operation(summary = "메인 화면과 대화 목록 조회")
    fun main(
        authentication: Authentication,
        @RequestParam(required = false) searchKeyword: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ) = chats.main(authentication.name, searchKeyword, page, size)

    @PostMapping("/chats")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "새 대화 생성")
    fun create(authentication: Authentication, @Valid @RequestBody request: CreateChatRequest = CreateChatRequest()) = chats.create(authentication.name, request)

    @GetMapping("/chats/{id}")
    @Operation(summary = "대화와 메시지 조회")
    fun get(
        authentication: Authentication,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "0") messagePage: Int,
        @RequestParam(defaultValue = "50") messageSize: Int,
    ) = chats.get(authentication.name, id, messagePage, messageSize)

    @PostMapping("/chats/{id}/messages")
    @Operation(summary = "메시지 전송 및 AI 답변 생성")
    fun send(authentication: Authentication, @PathVariable id: Long, @Valid @RequestBody request: SendMessageRequest) = chats.send(authentication.name, id, request)
}

@RestController
@RequestMapping("/api")
@Tag(name = "사용자 설정", description = "테마, 마이페이지, 개인 설정과 문의")
@SecurityRequirement(name = "bearerAuth")
class SettingsController(private val users: UserService) {
    @PatchMapping("/general")
    @Operation(summary = "테마 변경")
    fun general(authentication: Authentication, @Valid @RequestBody request: ThemeRequest) = mapOf("theme" to users.setTheme(authentication.name, request.theme))

    @GetMapping("/mypage")
    @Operation(summary = "마이페이지 조회")
    fun myPage(authentication: Authentication) = users.response(users.requireUser(authentication.name))

    @PatchMapping("/mypage")
    @Operation(summary = "마이페이지 수정")
    fun updateMyPage(authentication: Authentication, @Valid @RequestBody request: MyPageUpdateRequest) = users.update(authentication.name, request)

    @GetMapping("/setting/main")
    @Operation(summary = "설정 화면 조회")
    fun settingMain(authentication: Authentication) = users.settings(authentication.name)

    @PatchMapping("/setting")
    @Operation(summary = "개인 설정 변경")
    fun setting(authentication: Authentication, @Valid @RequestBody request: SettingUpdateRequest) = users.updateSettings(authentication.name, request)

    @PostMapping("/setting/inquiry")
    @Operation(summary = "문의 접수")
    fun inquiry(authentication: Authentication, @Valid @RequestBody request: InquiryRequest): ResponseEntity<MessageEnvelope> {
        users.submitInquiry(authentication.name, request.content)
        return ResponseEntity.accepted().body(MessageEnvelope("문의가 접수되었습니다"))
    }
}
