package com.travel.agent.rag;

import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索与问答。架构原则：
 * 静态知识（攻略/介绍/注意事项）→ RAG；实时信息（天气/价格/排队）→ Tools。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final LlmClient llmClient;

    public record RagAnswer(String message, List<String> sources) {}

    public List<VectorSearchResult> search(String query, String city, int topK) {
        float[] vector = embeddingService.embed(query);
        return vectorStore.search(vector, city, topK);
    }

    public RagAnswer answer(String question, String city) {
        List<VectorSearchResult> results = search(question, city, 3);
        if (results.isEmpty()) {
            return new RagAnswer("知识库中暂无相关资料，你可以换个问法，或直接告诉我你的旅行需求。", List.of());
        }
        String context = results.stream()
                .map(r -> "【" + r.getDocument().getTitle() + "】" + r.getChunk().getContent())
                .collect(Collectors.joining("\n\n"));
        List<String> sources = results.stream()
                .map(r -> r.getDocument().getTitle())
                .distinct()
                .toList();

        String llmReply = null;
        if (llmClient.available()) {
            llmReply = llmClient.chat(List.of(
                    LlmMessage.system("你是旅行助手，仅根据提供的知识库内容回答问题，回答简洁友好。若知识库内容不足以回答，请如实说明。"),
                    LlmMessage.user("知识库内容：\n" + context + "\n\n用户问题：" + question)
            ), 0.3);
        }
        if (llmReply != null) {
            return new RagAnswer(llmReply, sources);
        }
        // 规则降级：直接拼接最相关知识片段
        StringBuilder sb = new StringBuilder("根据旅游知识库：\n");
        for (VectorSearchResult r : results) {
            String snippet = r.getChunk().getContent();
            if (snippet.length() > 160) {
                snippet = snippet.substring(0, 160) + "……";
            }
            sb.append("· ").append(snippet).append("（来源：").append(r.getDocument().getTitle()).append("）\n");
        }
        return new RagAnswer(sb.toString().trim(), sources);
    }
}
