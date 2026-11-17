package kr.hs.gsm.hopes.feature

import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "개인정보 처리방침")
class PrivacyPolicyController {
    @GetMapping("/api/privacy/policy", produces = [MediaType.TEXT_HTML_VALUE])
    fun policy(): ResponseEntity<Resource> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
            .body(ClassPathResource("policy/privacy.html"))
}
