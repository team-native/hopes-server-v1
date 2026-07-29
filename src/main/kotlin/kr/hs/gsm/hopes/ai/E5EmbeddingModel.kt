package kr.hs.gsm.hopes.ai

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * multilingual-e5-large 로컬 임베딩 (DJL + PyTorch). 외부 API 호출 없이 서버 내에서 벡터를 만든다.
 *
 * e5 계열 규약:
 *  - 입력에 "query: "(질문) / "passage: "(문서) 프리픽스를 붙여야 한다.
 *  - 마지막 히든 스테이트를 mean pooling 후 L2 정규화한 벡터를 쓴다 (translator가 처리).
 * 정규화된 벡터끼리는 내적 = 코사인 유사도.
 *
 * 모델은 최초 임베딩 시 지연 로드한다(부팅 차단 방지). PyTorch 네이티브 라이브러리와 모델 가중치는
 * DJL 캐시 디렉터리에 내려받아 재사용한다.
 */
@Service
class E5EmbeddingModel(
    @Value("\${hopes.ai.embedding-model-url}") private val modelUrl: String,
) : EmbeddingModel {
    private val log = LoggerFactory.getLogger(javaClass)

    private val modelLazy = lazy { loadModel() }

    private fun loadModel(): ZooModel<String, FloatArray> {
        log.info("[ai] 임베딩 모델 로드 중: {} (최초 실행 시 다운로드로 수 분 걸릴 수 있음)", modelUrl)
        val model = Criteria.builder()
            .setTypes(String::class.java, FloatArray::class.java)
            .optModelUrls(modelUrl)
            .optEngine("PyTorch")
            .optArgument("pooling", "mean")
            .optArgument("normalize", "true")
            .optTranslatorFactory(TextEmbeddingTranslatorFactory())
            .build()
            .loadModel()
        log.info("[ai] 임베딩 모델 로드 완료: {}", modelUrl)
        return model
    }

    override fun embed(texts: List<String>, taskType: String): List<DoubleArray> {
        if (texts.isEmpty()) return emptyList()
        val prefix = if (taskType == "RETRIEVAL_QUERY") "query: " else "passage: "
        val inputs = texts.map { prefix + it }
        // Predictor는 스레드 안전하지 않으므로 호출마다 새로 만들어 닫는다.
        modelLazy.value.newPredictor().use { predictor ->
            return predictor.batchPredict(inputs).map { vec -> DoubleArray(vec.size) { vec[it].toDouble() } }
        }
    }

    @PreDestroy
    fun close() {
        if (modelLazy.isInitialized()) modelLazy.value.close()
    }

    companion object {
        const val EMBED_DIM = 1024
    }
}
