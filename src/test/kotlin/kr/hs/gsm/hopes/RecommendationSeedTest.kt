package kr.hs.gsm.hopes

import kr.hs.gsm.hopes.feature.CategoryRepository
import kr.hs.gsm.hopes.feature.RecommendationRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

@DataJpaTest(
    properties =
        ["spring.datasource.url=jdbc:h2:mem:recommendation-seeds;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecommendationSeedTest
@Autowired
constructor(
    private val categories: CategoryRepository,
    private val recommendations: RecommendationRepository,
) {
    @Test
    fun `all departments specialties and independent combinations have curated templates`() {
        val departments =
            categories.findAllByKindAndIsActiveTrueOrderBySortOrderAscIdAsc("DEPARTMENT")
        val specialties =
            categories.findAllByKindAndIsActiveTrueOrderBySortOrderAscIdAsc("SPECIALTY")
        val rows = recommendations.findAllByIsActiveTrueOrderBySortOrderAscIdAsc()
        assertEquals(3, departments.size)
        assertEquals(9, specialties.size)
        assertEquals(83, rows.size)
        assertEquals(83, rows.map { it.content }.distinct().size)
        assertTrue(rows.all { it.content.isNotBlank() && it.content.length <= 500 })
        assertTrue((departments + specialties).all { it.description.isNotBlank() })
        assertTrue(rows.count { it.departmentId == null && it.specialtyId == null } >= 8)
        departments.forEach { d ->
            assertTrue(
                rows.count { it.departmentId == d.id && it.specialtyId == null } >= 4,
                d.name,
            )
            specialties.forEach { s ->
                assertTrue(
                    rows.any { it.departmentId == d.id && it.specialtyId == s.id },
                    "${d.name}/${s.name}",
                )
            }
        }
        specialties.forEach { s ->
            assertTrue(
                rows.count { it.departmentId == null && it.specialtyId == s.id } >= 4,
                s.name,
            )
        }
        assertTrue(
            rows.all { it.departmentId == null || it.departmentId in departments.map { d -> d.id } }
        )
        assertTrue(
            rows.all { it.specialtyId == null || it.specialtyId in specialties.map { s -> s.id } }
        )
        assertEquals(
            setOf(
                1L,
                2L,
                3L,
                4L,
                5L,
                6L,
                7L,
                101L,
                102L,
                103L,
                104L,
                105L,
                106L,
                107L,
                108L,
                109L,
                201L,
            ),
            rows.filter { it.id < 3001 }.map { it.id }.toSet(),
        )
    }
}
