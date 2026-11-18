package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第十五批 FR-02 / FR-04：任务图（DAG）的查询与并行契约。
 *
 * <p>一次规划不再是「固定阶段序但互不知情」的记录，而是一张可查询的任务图：
 * <ol>
 *   <li>依赖边齐全：PLAN 依赖 INTENT；PREVIEW 与 REVIEW 都依赖 PLAN；</li>
 *   <li>PREVIEW 与 REVIEW 是同一并行组：两行任务在 body 开始前创建（id 顺序稳定），
 *       执行时间区间真实重叠；</li>
 *   <li>新字段（dependsOn / attempt / replacesTaskId）对外可查，旧字段含义不变。</li>
 * </ol>
 */
class TaskDagTest extends IntegrationTestSupport {

    private JsonNode chat(Account account, String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    private JsonNode runDetailOfLatest(Account account) throws Exception {
        MvcResult runs = mockMvc.perform(get("/api/agent/runs?limit=5")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        String runId = body(runs).path("data").get(0).path("runId").asText();
        MvcResult detail = mockMvc.perform(get("/api/agent/runs/" + runId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(detail).path("data");
    }

    private static List<JsonNode> tasksOf(JsonNode detail) {
        List<JsonNode> tasks = new ArrayList<>();
        detail.path("tasks").forEach(tasks::add);
        return tasks;
    }

    private static JsonNode firstOfType(List<JsonNode> tasks, String taskType) {
        return tasks.stream()
                .filter(task -> taskType.equals(task.path("taskType").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少任务节点: " + taskType));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static LocalDateTime at(JsonNode task, String field) {
        return LocalDateTime.parse(task.path(field).asText());
    }

    @Test
    @DisplayName("一次规划的任务图可查：依赖边齐全、PREVIEW 与 REVIEW 时间区间重叠")
    void planningProducesQueryableTaskDag() throws Exception {
        Account account = registerNew();
        JsonNode planned = chat(account, "帮我规划上海三日游，2人，预算3000元");
        assertThat(planned.path("status").asText()).isEqualTo("NEED_CONFIRMATION");
        assertThat(planned.path("planId").asText()).isNotBlank();

        List<JsonNode> tasks = tasksOf(runDetailOfLatest(account));
        JsonNode intent = firstOfType(tasks, "INTENT");
        JsonNode plan = firstOfType(tasks, "PLAN");
        JsonNode preview = firstOfType(tasks, "PREVIEW");
        JsonNode review = firstOfType(tasks, "REVIEW");

        // 根节点无前置；PLAN → INTENT；PREVIEW / REVIEW → PLAN
        assertThat(strings(intent.path("dependsOn"))).isEmpty();
        assertThat(strings(plan.path("dependsOn"))).containsExactly(intent.path("taskId").asText());
        assertThat(strings(preview.path("dependsOn"))).containsExactly(plan.path("taskId").asText());
        assertThat(strings(review.path("dependsOn"))).containsExactly(plan.path("taskId").asText());

        // 首次派发：attempt=0、没有被替换的旧任务
        for (JsonNode task : List.of(intent, plan, preview, review)) {
            assertThat(task.path("attempt").asInt()).isZero();
            assertThat(task.path("replacesTaskId").isNull() || task.path("replacesTaskId").isMissingNode())
                    .as("首次派发不应有 replacesTaskId: " + task.path("taskType").asText())
                    .isTrue();
            assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
        }

        // 并行组：两行都在各自 body 开始前按声明顺序创建（PREVIEW 在 REVIEW 之前）
        List<String> types = tasks.stream().map(t -> t.path("taskType").asText()).toList();
        assertThat(types.indexOf("PREVIEW")).isLessThan(types.indexOf("REVIEW"));

        // 时间区间真实重叠：max(start) < min(end)
        LocalDateTime overlapStart = at(preview, "startedAt").isAfter(at(review, "startedAt"))
                ? at(preview, "startedAt") : at(review, "startedAt");
        LocalDateTime overlapEnd = at(preview, "endedAt").isBefore(at(review, "endedAt"))
                ? at(preview, "endedAt") : at(review, "endedAt");
        assertThat(overlapStart)
                .as("PREVIEW 与 REVIEW 应并行执行（时间区间必须有交集）")
                .isBefore(overlapEnd);
    }

    @Test
    @DisplayName("闲聊只有意图节点：依赖边为空，不产生预览/审核任务")
    void chitchatHasOnlyIntentNode() throws Exception {
        Account account = registerNew();
        chat(account, "你好");

        List<JsonNode> tasks = tasksOf(runDetailOfLatest(account));
        assertThat(tasks).hasSize(1);
        JsonNode intent = tasks.get(0);
        assertThat(intent.path("taskType").asText()).isEqualTo("INTENT");
        assertThat(strings(intent.path("dependsOn"))).isEmpty();
        assertThat(intent.path("status").asText()).isEqualTo("SUCCEEDED");
    }
}