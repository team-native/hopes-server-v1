package kr.hs.gsm.hopes.api

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(val status: HttpStatus, override val message: String) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApi(exception: ApiException) =
        ResponseEntity.status(exception.status)
            .body(mapOf("code" to exception.status.name, "message" to exception.message))

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException::class)
    fun handleUploadSize() =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(mapOf("code" to "PAYLOAD_TOO_LARGE", "message" to "파일 업로드 크기 제한을 초과했습니다"))

    @ExceptionHandler(
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class,
        org.springframework.web.bind.MissingServletRequestParameterException::class,
        org.springframework.web.multipart.support.MissingServletRequestPartException::class,
    )
    fun handleParameter() =
        ResponseEntity.badRequest()
            .body(mapOf("code" to "BAD_REQUEST", "message" to "요청 파라미터 형식을 확인해주세요"))

    @ExceptionHandler(
        org.springframework.orm.ObjectOptimisticLockingFailureException::class,
        org.springframework.dao.PessimisticLockingFailureException::class,
    )
    fun handleConcurrentChange() =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("code" to "CONFLICT", "message" to "동시에 변경되었습니다. 다시 시도해주세요"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException
    ): ResponseEntity<Map<String, Any>> {
        val errors =
            exception.bindingResult.fieldErrors.associate {
                it.field to (it.defaultMessage ?: "잘못된 값입니다")
            }
        return ResponseEntity.badRequest()
            .body(
                mapOf(
                    "code" to "BAD_REQUEST",
                    "message" to (errors.values.firstOrNull() ?: "입력값을 확인해주세요"),
                    "errors" to errors,
                )
            )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody() =
        ResponseEntity.badRequest()
            .body(mapOf("code" to "BAD_REQUEST", "message" to "요청 본문의 형식이나 값을 확인해주세요"))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataConflict() =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("code" to "CONFLICT", "message" to "이미 사용 중인 값이거나 저장할 수 없는 값입니다"))
}
