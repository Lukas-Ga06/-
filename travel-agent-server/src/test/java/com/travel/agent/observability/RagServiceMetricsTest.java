package com.travel.agent.observability;

import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.rag.EmbeddingService;
import com.travel.agent.rag.RagCitation;
import com.travel.agent.rag.RagService;
import com.travel.agent.rag.RagVersionService;
import com.travel.agent.rag.VectorSearchResult;
import com.travel.agent.rag.VectorStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RAG 检索指标（T5）：{@code travel.rag.retrieval{result=hit|miss}} 必须按<b>检索结果</b>记账。
 *
 * <p>命中率是"知识库是否真的在被用"的唯一量化口径：低于阈值拒答也仍然是 hit（检索有结果，
 * 只是问答层拒答）；检索异常不记账（否则故障会被伪装成 miss，拉低命中率却看不出问题）。
 */
class RagServiceMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TravelMetrics metrics = new TravelMetrics(registry, new LlmProperties());

    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final LlmClient llmClient = mock(LlmClient.class);
    private final RagVersionService versionService = mock(RagVersionService.class);

    private RagService service;

    @BeforeEach
    void setUp() {
        when(embedding.embed(any())).thenReturn(new float[]{1.0f, 0.0f});
        when(versionService.versionNoOf(any())).thenReturn(1);
        service = new RagService(embedding, vectorStore, llmClient, versionService);
        ReflectionTestUtils.setField(service, "travelMetrics", metrics);
    }

    /** 未创建过的 tag 组合按 0 处理（registry.get 会抛 MeterNotFoundException，不适合断言"没记"） */
    private double retrievalCount(String result) {
        return registry.find(TravelMetrics.RAG_RETRIEVAL).tag("result", result).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private VectorSearchResult hit() {
        RagDocument document = RagDocument.builder().id(1L).title("上海亲子攻略").source("内置知识库").build();
        RagChunk chunk = RagChunk.builder().id(2L).documentId(1L).versionId(3L).content("外滩适合傍晚").build();
        return new VectorSearchResult(chunk, document, 0.62);
    }

    @Test
    @DisplayName("检索返回非空 → result=hit")
    void nonEmptyResultCountsHit() {
        when(vectorStore.search(any(), any(), any(), anyInt())).thenReturn(List.of(hit()));

        List<RagCitation> citations = service.searchWithCitations("上海带娃", "上海", null, 3);

        assertThat(citations).hasSize(1);
        assertThat(retrievalCount("hit")).isEqualTo(1.0);
        assertThat(retrievalCount("miss")).isZero();
    }

    @Test
    @DisplayName("检索返回空 → result=miss")
    void emptyResultCountsMiss() {
        when(vectorStore.search(any(), any(), any(), anyInt())).thenReturn(List.of());

        List<RagCitation> citations = service.searchWithCitations("南极科考", "上海", null, 3);

        assertThat(citations).isEmpty();
        assertThat(retrievalCount("miss")).isEqualTo(1.0);
        assertThat(retrievalCount("hit")).isZero();
    }

    @Test
    @DisplayName("检索异常不记账（故障不能被伪装成 miss），异常照常上抛")
    void thrownRetrievalCreatesNoMetric() {
        when(vectorStore.search(any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("vector store down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.searchWithCitations("上海", "上海", null, 3))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.find(TravelMetrics.RAG_RETRIEVAL).counters()).isEmpty();
    }

    @Test
    @DisplayName("未注入指标（纯单测直接 new）时检索照常工作")
    void worksWithoutMetrics() {
        when(vectorStore.search(any(), any(), any(), anyInt())).thenReturn(List.of(hit()));
        RagService withoutMetrics = new RagService(embedding, vectorStore, llmClient, versionService);

        assertThat(withoutMetrics.searchWithCitations("上海", "上海", null, 3)).hasSize(1);
    }
}