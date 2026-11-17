package kr.hs.gsm.hopes

import kr.hs.gsm.hopes.ai.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NavigationIntentTest {
    private class Stub(var result: String) : AiClient {
        override val hasKey = true
        var prompt = ""
        var turns = emptyList<Pair<String, String>>()
        override fun generate(systemPrompt: String, turns: List<Pair<String, String>>): String {
            prompt = systemPrompt
            this.turns = turns
            return result
        }
        override fun generateWithAttachments(systemPrompt: String, turns: List<Pair<String, String>>, attachments: List<AiAttachment>) = error("unused")
        override fun classifyMealLookup(turns: List<Pair<String, String>>) = false
    }

    @Test
    fun `timetable request is classified by model without forcing a map`() {
        val client = Stub("OTHER")
        val turns = listOf("user" to "보건실 어디야", "user" to "2학년 1반 시간표 알려줘")
        assertFalse(client.classifyNavigationLookup(turns))
        assertEquals(turns, client.turns)
        assertTrue(client.prompt.contains("2학년 1반 시간표 알려줘"))
        client.result = " NAVIGATION\n"
        assertTrue(client.classifyNavigationLookup(listOf("user" to "2학년 1반 어디야")))
        for (invalid in listOf("", "OTHER", "NAVIGATION because classroom", "```NAVIGATION```")) {
            client.result = invalid
            assertFalse(client.classifyNavigationLookup(turns))
        }
    }
}
