package kr.hs.gsm.hopes

import kr.hs.gsm.hopes.feature.placeMatches
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CampusPlaceNamesTest {
    @Test fun `synonyms shorthand whitespace and nearby expressions match actual registered names`() {
        for (query in listOf("2-3반 앞", "2학년3반", "2 학년 3 반 근처", "2-3")) assertTrue(placeMatches("2학년 3반", query), query)
        for (query in listOf("도서관", "도서관 앞", "library", "도서실 옆")) assertTrue(placeMatches("도서실", query), query)
        assertTrue(placeMatches("성고충상담실/보건실", "양호실"))
        assertTrue(placeMatches("식생활관", "급식실"))
        assertTrue(placeMatches("홈베이스", "홈베 위치"))
        assertFalse(placeMatches("2학년 3반", "3-2반 앞"))
        assertFalse(placeMatches("도서실", "수영장"))
    }
}
