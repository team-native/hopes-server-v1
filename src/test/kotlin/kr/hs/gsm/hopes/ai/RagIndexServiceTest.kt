package kr.hs.gsm.hopes.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.DefaultResourceLoader
import java.io.File

class RagIndexServiceTest {

    @TempDir
    lateinit var tempDir: File

    private fun writeChunks(text: String): File {
        val file = File(tempDir, "chunks.jsonl")
        file.writeText("""{"chunk_id":"c1","text":"$text"}""" + "\n", Charsets.UTF_8)
        return file
    }

    private fun service(embeddingModel: EmbeddingModel, chunksFile: File): RagIndexService =
        RagIndexService(
            client = FakeGeminiClient(),
            embeddingModel = embeddingModel,
            objectMapper = jacksonObjectMapper(),
            resourceLoader = DefaultResourceLoader(),
            enabledFlag = true,
            chunksPath = chunksFile.toURI().toString(),
            cachePath = File(tempDir, "cache.json").absolutePath,
            embeddingModelName = "test-embedding-model",
            topK = 3,
            minSimilarity = 0.55,
            indexRetrySeconds = 0,
        )

    private fun buildAndAwait(service: RagIndexService) {
        service.onApplicationReady()
        val deadline = System.currentTimeMillis() + 10_000
        while (!service.ready && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(service.ready, "인덱스가 제한 시간 안에 준비되지 않았습니다")
    }

    @Test
    fun `임베딩이 실패해도 재시도해서 인덱스를 완성한다`() {
        val embedding = FakeEmbeddingModel().apply { embedFailures = 2 }
        buildAndAwait(service(embedding, writeChunks("기숙사 안내")))
        assertEquals(listOf("기숙사 안내"), embedding.embeddedTexts)
    }

    @Test
    fun `청크 텍스트가 바뀌면 같은 chunk_id라도 다시 임베딩한다`() {
        buildAndAwait(service(FakeEmbeddingModel(), writeChunks("기숙사 안내 v1")))

        // 같은 chunk_id에 내용만 수정 → 캐시를 무시하고 다시 임베딩해야 한다.
        val changed = FakeEmbeddingModel()
        buildAndAwait(service(changed, writeChunks("기숙사 안내 v2")))
        assertEquals(listOf("기숙사 안내 v2"), changed.embeddedTexts)

        // 내용이 같으면 캐시 재사용 → 임베딩 호출 없음.
        val unchanged = FakeEmbeddingModel()
        buildAndAwait(service(unchanged, writeChunks("기숙사 안내 v2")))
        assertTrue(unchanged.embeddedTexts.isEmpty())
    }

    @Test
    fun `약어가 있으면 정식 명칭을 덧붙여 검색 질의를 확장한다`() {
        val service = service(FakeEmbeddingModel(), writeChunks("기숙사 안내"))
        assertTrue(service.expandQuery("기자위 언제 모여?").contains("기숙사자치위원회"))
        assertEquals("안녕", service.expandQuery("안녕"))
    }
}
