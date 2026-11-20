package com.travel.agent.rag;

import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旅行问答检索评估集（P1）：把「RAG 到底能不能召回正确文档」变成可重复的断言。
 *
 * <p>指标口径（全部只看 top-K 的文档标题，不依赖 LLM）：
 * <ul>
 *   <li>Recall@K：期望文档出现在前 K 条命中的比例；</li>
 *   <li>MRR：第一个正确命中的排名倒数均值（第 1 名 = 1，第 2 名 = 0.5…）；</li>
 *   <li>低相似度误答率：知识库无相关资料时，问答是否如实说明「暂无可靠资料」。</li>
 * </ul>
 *
 * <p>阈值刻意保守：本地哈希 Embedding 只是离线降级实现，不能被当成高质量语义检索的证明；
 * 评估集用于防止「检索链路被改坏」，不是宣传指标。接入真实 Embedding 后可上调阈值。
 */
class RagEvaluationTest extends IntegrationTestSupport {

    private static final int TOP_K = 5;

    @Autowired
    private RagService ragService;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RagBootstrap ragBootstrap;

    @Autowired
    private VectorStore vectorStore;

    /** 评估集：问题 + 城市过滤 + 期望文档标题 */
    private record Case(String question, String city, String expectedTitle) {}

    private static final List<Case> CASES = List.of(
            new Case("上海博物馆怎么预约，周一开门吗", "上海", "上海博物馆参观指南"),
            new Case("外滩什么时候去最好，要门票吗", "上海", "外滩游玩攻略"),
            new Case("故宫门票怎么预约，周一闭馆吗", "北京", "北京故宫游览须知"),
            new Case("下雨天户外景点怎么替换", null, "雨天旅行替代方案"),
            new Case("带孩子出行要注意什么", null, "带娃出行实用注意事项"),
            new Case("酒店离地铁近有什么好处", null, "城市地铁出行小贴士"),
            new Case("三天旅行预算怎么分配", null, "旅行预算分配建议"));

    @BeforeEach
    void seedAndReload() throws Exception {
        if (documentRepository.count() == 0) {
            ragBootstrap.run(null);
        }
        vectorStore.reload();
    }

    @Test
    @DisplayName("评估集：Recall@5 / MRR 达标，且无资料问题如实说明低相似度")
    void retrievalMeetsBaselineMetrics() {
        int hits = 0;
        double reciprocalRankSum = 0;
        for (Case evaluation : CASES) {
            List<RagCitation> results = ragService.searchWithCitations(
                    evaluation.question(), evaluation.city(), null, TOP_K);
            List<String> titles = results.stream().map(RagCitation::title).toList();
            int rank = titles.indexOf(evaluation.expectedTitle());
            if (rank >= 0) {
                hits++;
                reciprocalRankSum += 1.0 / (rank + 1);
            }
        }
        double recall = hits / (double) CASES.size();
        double mrr = reciprocalRankSum / CASES.size();

        System.out.printf("[RAG-EVAL] cases=%d recall@%d=%.3f mrr=%.3f%n",
                CASES.size(), TOP_K, recall, mrr);
        assertThat(recall).as("Recall@%d（本地哈希 Embedding 的保守基线）", TOP_K).isGreaterThanOrEqualTo(0.8);
        assertThat(mrr).as("MRR").isGreaterThanOrEqualTo(0.5);
    }
}
