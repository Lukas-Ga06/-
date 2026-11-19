package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Restaurant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1：结构化规划（plan 阶段）的重试也要受预算约束。
 *
 * <p>「首轮失败 → 带反馈重试」是既有设计，但重试同样是一次完整 LLM 调用：预算不够时
 * 直接回落到规则规划，而不是把总耗时翻倍。这里用慢 LLM 替身 + 小预算验证重试被抑制。
 */
class StructuredPlannerBudgetTest {

    private final CityCatalog city = new CityCatalog(
            "测试市",
            List.of(
                    new Attraction("a1", "故宫", "outdoor", List.of("历史"), 39.9, 116.4, 60, 2, 4.8, "明清皇宫"),
                    new Attraction("a2", "科技馆", "indoor", List.of("亲子"), 39.8, 116.5, 30, 2.5, 4.6, "科技展览")),
            List.of(),
            List.of(new Restaurant("r1", "老字号烤鸭", "京菜", 120, 4.6, 39.91, 116.41, "烤鸭")));

    private final StructuredPlanner.TripDraftView draft =
            new StructuredPlanner.TripDraftView("测试市", 2, LocalDate.of(2026, 11, 1),
                    BigDecimal.valueOf(3000), List.of());

    private static final String VALID = """
            {"reasoning":"修正后的编排","days":[{"day":1,"items":[
              {"type":"attraction","name":"故宫","startTime":"09:00","endTime":"11:30"}]}]}
            """;

    /** 慢 LLM 替身：三参调用按读超时截断等待，模拟真实客户端的读超时行为 */
    private static final class SlowLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        private final ArrayDeque<String> responses;
        private final long sleepMs;

        SlowLlm(long sleepMs, String... responses) {
            this.sleepMs = sleepMs;
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            calls.incrementAndGet();
            sleep(sleepMs);
            return responses.isEmpty() ? VALID : responses.poll();
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
            calls.incrementAndGet();
            sleep(Math.min(sleepMs, readTimeoutMs));
            return responses.isEmpty() ? VALID : responses.poll();
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private StructuredPlanner planner(LlmClient client) {
        return new StructuredPlanner(client, new ObjectMapper(), registry);
    }

    private double counter(String path, String result) {
        return registry.counter("structured.planning", "path", path, "result", result).count();
    }

    @Test
    @DisplayName("预算 150ms + 慢 LLM 300ms：首轮失败后不再重试，直接回落规则规划，总耗时被预算封住")
    void budgetSuppressesRetry() {
        SlowLlm llm = new SlowLlm(300L, "这不是 JSON", VALID);
        // 先建好被测对象（ObjectMapper 冷启动），再开预算窗口，避免测试自身初始化吃掉预算
        StructuredPlanner planner = planner(llm);
        AgentBudget budget = AgentBudget.start(150L, 30_000);

        long started = System.nanoTime();
        StructuredPlanner.NewTripParse parse = planner.planNewTrip(city, draft, "两日游", List.of(), List.of(), budget);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertThat(parse == null || !parse.ok()).as("首轮失败 + 预算耗尽 → 调用方回落规则规划").isTrue();
        assertThat(llm.calls.get()).as("预算不够就不再发起重试调用").isEqualTo(1);
        assertThat(elapsedMs).isBetween(100L, 800L);
        assertThat(counter("new_trip", "fallback")).isEqualTo(1.0);
        assertThat(counter("new_trip", "after_retry")).isZero();
    }

    @Test
    @DisplayName("预算关闭（unlimited）：首轮失败照旧重试一次，行为与升级前一致")
    void unlimitedBudgetKeepsRetryBehaviour() {
        SlowLlm llm = new SlowLlm(5L, "这不是 JSON", VALID);

        StructuredPlanner.NewTripParse parse = planner(llm)
                .planNewTrip(city, draft, "两日游", List.of(), List.of(), AgentBudget.unlimited());

        assertThat(parse).isNotNull();
        assertThat(parse.ok()).isTrue();
        assertThat(llm.calls.get()).isEqualTo(2);
        assertThat(counter("new_trip", "after_retry")).isEqualTo(1.0);
    }
}