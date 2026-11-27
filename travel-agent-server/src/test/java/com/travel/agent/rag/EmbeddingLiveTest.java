package com.travel.agent.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 真实 Embedding 的**质量验收**（人工按需运行，不进 CI）。
 *
 * <p>为什么单独一类：{@code capability-boundaries} 至今写的是「真实 Embedding 的质量验收没有做，
 * 不要把 RAG 引用当作语义正确证据」——桩/哈希向量证明不了语义空间是否对得上，
 * 必须有真实 Key + 真实 {@code /embeddings} 端点。
 *
 * <pre>
 * $env:EMBEDDING_LIVE = "true"
 * $env:EMBEDDING_ENABLED = "true"
 * $env:EMBEDDING_BASE_URL = "https://api.siliconflow.cn/v1"
 * $env:EMBEDDING_API_KEY = "<key>"
 * $env:EMBEDDING_MODEL = "BAAI/bge-m3"
 * mvn -B -Dtest=EmbeddingLiveTest test
 * </pre>
 *
 * <p><b>断言口径</b>：不断言具体余弦值（模型/版本会变），只断言「同义/同主题 > 无关主题 + 余量」；
 * 这是质量验收，不是排序基准。若模型换了，余量可能需重新标定——失败时先看是否换了模型。
 *
 * <p>成本：一次运行 6 次短文本 embedding，按 bge-m3 公开价目表可以忽略；不重建知识库、不写库。
 */
@EnabledIfEnvironmentVariable(named = "EMBEDDING_LIVE", matches = "true")
@SpringBootTest
@ActiveProfiles("test")
class EmbeddingLiveTest {

    /** 余弦相似度余量：同主题应明显高于无关主题（不是精确阈值，防上游模型波动打脸） */
    private static final double MARGIN = 0.05;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private EmbeddingProperties properties;

    @Test
    @DisplayName("真实服务被激活：配置可用、维度和向量的归一化正确")
    void realProviderIsActive() {
        assertThat(properties.usable())
                .as("EMBEDDING_ENABLED=true 且 EMBEDDING_API_KEY 非空，才会启用真实服务")
                .isTrue();
        assertThat(embeddingService)
                .as("启用后 @Primary 的真实实现必须接管（不是本地哈希兜底）")
                .isInstanceOf(OpenAiCompatibleEmbeddingService.class);

        float[] vector = embeddingService.embed("杭州西湖");
        assertThat(vector).as("真实 embedding 不能返回空向量").isNotEmpty();
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        assertThat(Math.sqrt(norm)).as("服务应输出归一化向量，余弦相似度才可直接点积").isCloseTo(1.0, within(1e-3));
    }

    @Test
    @DisplayName("语义质量：同义地标明显比无关主题更近（两座城市各一组）")
    void semanticSimilarityBeatsUnrelatedTopic() {
        float[] westLake = embeddingService.embed("杭州西湖景区");
        float[] westLakeAlias = embeddingService.embed("西湖风景名胜区");
        float[] carRepair = embeddingService.embed("汽车维修保养");

        double sameTopic = cosine(westLake, westLakeAlias);
        double unrelated = cosine(westLake, carRepair);
        assertThat(sameTopic)
                .as("同义地标（杭州西湖 ↔ 西湖风景名胜区）余弦 %.4f 应高于无关主题 %.4f + %.2f",
                        sameTopic, unrelated, MARGIN)
                .isGreaterThan(unrelated + MARGIN);

        float[] forbiddenCity = embeddingService.embed("北京故宫博物院");
        float[] forbiddenCityAlias = embeddingService.embed("紫禁城");
        float[] phoneRepair = embeddingService.embed("手机屏幕维修");

        double sameTopic2 = cosine(forbiddenCity, forbiddenCityAlias);
        double unrelated2 = cosine(forbiddenCity, phoneRepair);
        assertThat(sameTopic2)
                .as("同义地标（北京故宫 ↔ 紫禁城）余弦 %.4f 应高于无关主题 %.4f + %.2f",
                        sameTopic2, unrelated2, MARGIN)
                .isGreaterThan(unrelated2 + MARGIN);
    }

    private static double cosine(float[] left, float[] right) {
        int size = Math.min(left.length, right.length);
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }
}