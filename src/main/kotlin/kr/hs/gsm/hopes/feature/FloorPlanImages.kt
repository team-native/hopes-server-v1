package kr.hs.gsm.hopes.feature

import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@Tag(name = "교내 길찾기")
class FloorPlanImageController(private val campus: CampusService) {
    @GetMapping("/api/campus/floor-plans/{planId}/image", produces = [MediaType.IMAGE_PNG_VALUE])
    fun image(@PathVariable planId: String): ResponseEntity<Resource> {
        if (!planId.matches(Regex("[A-Za-z0-9-]+"))) fail(HttpStatus.NOT_FOUND, "도면을 찾을 수 없습니다")
        val allowed =
            campus.referenceData()?.buildings?.any { b ->
                b.floorPlans.any { p ->
                    b.approved(p) && (p.id == planId || p.id.replace("lab-", "main-") == planId)
                }
            } == true
        val resource = ClassPathResource("campus/floorplans/$planId.png")
        if (!allowed || !resource.exists()) fail(HttpStatus.NOT_FOUND, "공개된 도면을 찾을 수 없습니다")
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.IMAGE_PNG)
            .body(resource)
    }
}
