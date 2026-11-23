package com.travel.agent.tool.impl;

import com.travel.agent.rag.RagCitation;
import com.travel.agent.rag.RagService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * query_travel_knowledge：检索旅行知识库（只读）。
 *
 * <p>P1 把 RAG 接进 Planner 的研究链路：研究循环可自己决定查静态知识
 * （攻略/注意事项/交通/预约规则），拿到的是**带引用的证据**：
 * documentId / version / chunkId / score / source / snippet。方案与 SSE 事件据此可追溯，
 * 而不是让模型凭记忆编造。
 */
@Component
@RequiredArgsConstructor
public class QueryTravelKnowledgeTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 8;

    private final RagService ragService;

    @Override
    public String getName() {
        return "query_travel_knowledge";
    }

    @Override
    public String getDescription() {
        return "检索旅行知识库，获取目的地的攻略、交通、预约与注意事项（返回文档 id、版本、片段与相似度）";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("query", true, "string", "检索词"),
                new ToolParameter("city", false, "string", "城市"),
                new ToolParameter("topK", false, "number", "返回条数 1-8"),
                new ToolParameter("userId", false, "number", "用户 ID（通常不需要）"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String query = ToolArgs.string(args, "query", "");
        String city = ToolArgs.string(args, "city");
        Long topKValue = ToolArgs.longValue(args, "topK");
        int topK = topKValue == null ? DEFAULT_TOP_K
                : (int) Math.max(1, Math.min(topKValue, MAX_TOP_K));
        if (query.isEmpty()) {
            return List.of();
        }
        Long userId = ToolArgs.longValue(args, "userId");
        List<RagCitation> hits = ragService.searchWithCitations(query, city, userId, topK);
        return hits.stream().map(QueryTravelKnowledgeTool::toMap).toList();
    }

    private static Map<String, Object> toMap(RagCitation citation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("documentId", citation.documentId());
        row.put("version", citation.version());
        row.put("chunkId", citation.chunkId());
        row.put("score", citation.score());
        row.put("source", citation.source());
        row.put("title", citation.title());
        row.put("snippet", citation.snippet());
        return row;
    }
}
