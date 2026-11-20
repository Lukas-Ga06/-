package com.travel.agent.rag;

import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.llm.LlmClient;
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
 * 低相似度拒答（P1）：阈值逻辑必须确定性可测，不能拿本地哈希 Embedding 的分数当质量证明。
 *
 * <p>这里直接桩掉向量检索，返回一个 0.01 分的命中：问答必须如实说「暂无可靠资料」，
 * 不能把近乎无关的片段包装成知识依据。阈值由 {@code travel.rag.min-score} 配置（默认 0.05）。
 */
class RagServiceThresholdTest {

    private RagService service(double score) {
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.embed(any())).thenReturn(new float[]{1.0f, 0.0f});
        VectorStore store = mock(VectorStore.class);
        RagDocument document = RagDocument.builder()
                .id(1L).title("无关文档").source("内置知识库").build();
        RagChunk chunk = RagChunk.builder()
                .id(2L).documentId(1L).versionId(null).content("与问题无关的片段").build();
        when(store.search(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new VectorSearchResult(chunk, document, score)));
        LlmClient llm = mock(LlmClient.class);
        when(llm.available()).thenReturn(false);
        RagVersionService versionService = mock(RagVersionService.class);
        RagService ragService = new RagService(embedding, store, llm, versionService);
        ReflectionTestUtils.setField(ragService, "minScore", 0.05);
        return ragService;
    }

    @Test
    @DisplayName("低于阈值：拒答并说明暂无可靠资料，不返回引用")
    void belowThresholdRefusesToAnswer() {
        RagService.RagAnswer answer = service(0.01).answer("南极科考签证怎么办", null, null);

        assertThat(answer.citations()).isEmpty();
        assertThat(answer.sources()).isEmpty();
        assertThat(answer.message()).contains("暂无可靠资料");
    }

    @Test
    @DisplayName("达到阈值：正常给出片段与引用（阈值不是把检索关掉）")
    void aboveThresholdStillAnswers() {
        RagService.RagAnswer answer = service(0.5).answer("上海亲子怎么安排", null, null);

        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.message()).contains("无关文档");
    }
}
