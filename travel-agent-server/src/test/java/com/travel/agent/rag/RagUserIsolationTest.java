package com.travel.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个人知识库用户隔离（P0-3）。
 *
 * 回归背景：knowledge/add 落库时不带 userId，knowledge/search 用 city=null 检索，
 * 而 matchesCity 在 queryCity==null 时放行全部文档 → A 上传的私有文本能被任意 B 账号搜到
 * （实测 B 用 A 的标记词直接命中，score 0.691），同一管道还会把他人文本注入 Agent 的 RAG 上下文。
 *
 * 修复后：RagDocument.userId（NULL = 公共知识）+ 检索时按
 * 「doc.userId == null || doc.userId.equals(currentUserId)」过滤。
 */
class RagUserIsolationTest extends IntegrationTestSupport {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RagBootstrap ragBootstrap;

    @BeforeEach
    void seedPublicKnowledge() throws Exception {
        // 公共知识（内置 knowledge.json 种子）由 RagBootstrap 同步；RagCityFilterTest 也依赖同样的前置
        if (documentRepository.count() == 0) {
            ragBootstrap.run(null);
        }
        vectorStore.reload();
    }

    private void addKnowledge(Account account, String title, String content) throws Exception {
        mockMvc.perform(post("/api/knowledge/add")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title, "content", content))))
                .andExpect(status().isOk());
        // controller 内部已 reload；这里再兜一次，避免索引刷新时序影响断言
        vectorStore.reload();
    }

    private JsonNode search(Account account, String query) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/knowledge/search")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("query", query))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    private static List<String> titles(JsonNode data) {
        List<String> titles = new ArrayList<>();
        data.forEach(node -> titles.add(node.path("title").asText()));
        return titles;
    }

    private static List<String> snippets(JsonNode data) {
        List<String> snippets = new ArrayList<>();
        data.forEach(node -> snippets.add(node.path("snippet").asText()));
        return snippets;
    }

    @Test
    @DisplayName("A 的私有知识 B 搜不到；A 自己能搜到；公共知识仍然所有人可见")
    void privateKnowledgeIsIsolatedButPublicKnowledgeIsShared() throws Exception {
        Account owner = registerNew();
        Account stranger = registerNew();
        String marker = "ZQX-ISOLATION-" + UUID.randomUUID().toString().substring(0, 8);
        String privateTitle = "A 的私人出行笔记-" + marker;
        String privateContent = "这一整段只属于 A：护照号与银行卡信息都写在这里。" + marker + "。请勿外传。";

        addKnowledge(owner, privateTitle, privateContent);

        // 1) B 搜同一个标记词：拿不到 A 的任何片段
        JsonNode strangerResult = search(stranger, marker);
        assertThat(titles(strangerResult))
                .as("B 不应检索到 A 的私有文档")
                .doesNotContain(privateTitle);
        assertThat(snippets(strangerResult))
                .as("B 的结果里不应出现 A 的私有文本")
                .noneMatch(snippet -> snippet.contains(marker));

        // 2) A 搜同一个标记词：能命中自己的文档
        JsonNode ownerResult = search(owner, marker);
        assertThat(titles(ownerResult))
                .as("A 应能检索到自己上传的私有文档")
                .contains(privateTitle);
        assertThat(snippets(ownerResult)).anyMatch(snippet -> snippet.contains(marker));

        // 3) 公共知识（内置种子，city 非空 / 通用）所有人可见，不受用户过滤影响
        JsonNode publicResult = search(stranger, "出行有什么注意事项");
        assertThat(publicResult.size())
                .as("公共知识必须仍能被普通用户检索到")
                .isPositive();
        assertThat(titles(publicResult)).allMatch(title -> !title.isBlank());
    }

    @Test
    @DisplayName("用户过滤规则：公共知识（userId 为空）始终可见，个人知识只对归属用户可见")
    void matchesUserRule() {
        assertThat(InMemoryVectorStore.matchesUser(null, null)).isTrue();
        assertThat(InMemoryVectorStore.matchesUser(null, 7L)).isTrue();
        assertThat(InMemoryVectorStore.matchesUser(7L, 7L)).isTrue();
        assertThat(InMemoryVectorStore.matchesUser(7L, 8L)).isFalse();
        assertThat(InMemoryVectorStore.matchesUser(7L, null)).isFalse();
    }
}