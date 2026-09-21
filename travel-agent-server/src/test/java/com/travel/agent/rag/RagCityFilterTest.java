package com.travel.agent.rag;

import com.travel.agent.rag.RagService;
import com.travel.agent.rag.VectorStore;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 城市过滤：指定城市时，通用知识必须继续参与检索。
 * 回归背景：旧实现只保留 city 完全相等的文档，导致「有行程时问注意事项」永远查不到通用文档，
 * 目录外城市（无城市级文档）直接得到空结果。
 */
class RagCityFilterTest extends IntegrationTestSupport {

    @Autowired
    private RagService ragService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RagBootstrap ragBootstrap;

    @BeforeEach
    void seedAndReload() throws Exception {
        if (documentRepository.count() == 0) {
            ragBootstrap.run(null);
        }
        vectorStore.reload();
    }

    private List<String> citiesOf(String query, String city) {
        return ragService.search(query, city, 5).stream()
                .map(r -> r.getDocument().getCity())
                .toList();
    }

    @Test
    @DisplayName("城市过滤规则：通用/未标注城市的文档始终命中")
    void cityFilterKeepsGenericDocuments() {
        assertThat(InMemoryVectorStore.matchesCity("通用", "上海")).isTrue();
        assertThat(InMemoryVectorStore.matchesCity(null, "上海")).isTrue();
        assertThat(InMemoryVectorStore.matchesCity("", "上海")).isTrue();
        assertThat(InMemoryVectorStore.matchesCity("上海", "上海")).isTrue();
        assertThat(InMemoryVectorStore.matchesCity("北京", "上海")).isFalse();
        // 未指定城市时全库检索
        assertThat(InMemoryVectorStore.matchesCity("北京", null)).isTrue();
        assertThat(InMemoryVectorStore.matchesCity("北京", "  ")).isTrue();
    }

    @Test
    @DisplayName("指定城市时，通用知识仍会被检索到")
    void genericDocsAreSearchableWithCity() {
        List<String> cities = citiesOf("出行有什么注意事项", "上海");
        assertThat(cities).isNotEmpty();
        assertThat(cities).contains("通用");
    }

    @Test
    @DisplayName("目录外城市（没有城市级文档）不再返回空结果")
    void unknownCityFallsBackToGenericDocs() {
        List<String> cities = citiesOf("有什么要注意的", "洛阳");
        assertThat(cities).isNotEmpty();
        assertThat(cities).allMatch(city -> city == null || city.isBlank() || "通用".equals(city));
    }

    @Test
    @DisplayName("不带城市时全库检索")
    void withoutCitySearchesEverything() {
        assertThat(ragService.search("注意事项", null, 5)).isNotEmpty();
    }
}
