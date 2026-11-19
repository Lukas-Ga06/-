package com.travel.agent.agent;

import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 端到端时间预算的基础语义（纯单测，不启 Spring 上下文）。
 *
 * <p>覆盖三件事：关闭开关时恒放行（行为与升级前一致）、预算耗尽后阶段/调用被拒绝、
 * 单次 LLM 读超时按剩余预算封顶（这是「最后一个阶段也不会越过 deadline」的关键）。
 */
class AgentBudgetTest {

    /** 记录调用次数与「是否走了带读超时的三参路径」的桩客户端 */
    private static final class RecordingLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> timeouts = new ArrayList<>();

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            calls.incrementAndGet();
            return "two-arg";
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
            calls.incrementAndGet();
            timeouts.add(readTimeoutMs);
            return "three-arg";
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    @Test
    @DisplayName("关闭预算：所有阶段检查恒放行，LLM 调用保持原来的两参路径")
    void unlimitedNeverBlocksAndKeepsLegacyCallPath() {
        AgentBudget budget = AgentBudget.unlimited();
        assertThat(budget.isEnabled()).isFalse();
        assertThat(budget.isExhausted()).isFalse();
        assertThat(budget.allows(0L)).isTrue();
        assertThat(budget.allows(Long.MAX_VALUE)).isTrue();
        assertThat(budget.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)).isTrue();

        RecordingLlm llm = new RecordingLlm();
        assertThat(budget.chat(llm, List.of(LlmMessage.user("hi")), 0.0)).isEqualTo("two-arg");
        assertThat(llm.calls.get()).isEqualTo(1);
        assertThat(llm.timeouts).as("预算关闭时不允许改变任何调用方式").isEmpty();
    }

    @Test
    @DisplayName("预算耗尽（total=0）：阶段门禁与 LLM 调用全部拒绝")
    void exhaustedBudgetBlocksEverything() {
        AgentBudget budget = AgentBudget.start(0L, 30_000);
        assertThat(budget.isEnabled()).isTrue();
        assertThat(budget.remainingMs()).isZero();
        assertThat(budget.isExhausted()).isTrue();
        assertThat(budget.allows(1L)).isFalse();
        assertThat(budget.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)).isFalse();
    }

    @Test
    @DisplayName("单次 LLM 读超时按剩余预算封顶，且不放大配置里的全局读超时")
    void llmReadTimeoutIsCappedByRemainingBudget() throws Exception {
        AgentBudget budget = AgentBudget.start(1000L, 30_000);
        Thread.sleep(60L);
        int timeout = budget.llmReadTimeoutMs();
        // 剩余约 940ms：读超时必须被压到「剩余预算」以内（而不是配置的 30s）
        assertThat(timeout).isPositive().isLessThan(30_000).isLessThanOrEqualTo(1000);
        assertThat(budget.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)).isTrue();

        RecordingLlm llm = new RecordingLlm();
        budget.chat(llm, List.of(LlmMessage.user("hi")), 0.0);
        assertThat(llm.timeouts).singleElement().satisfies(sent -> assertThat(sent).isLessThan(30_000));

        // 剩余预算大于配置读超时时，用配置值（不能把 500ms 放大成 10s）
        AgentBudget roomy = AgentBudget.start(10_000L, 500);
        assertThat(roomy.llmReadTimeoutMs()).isEqualTo(500);
    }

    @Test
    @DisplayName("剩余时间不足一次调用下限时 allowsLlmCall=false：不发起注定读超时的请求")
    void rejectsCallsThatWouldInevitablyTimeOut() {
        // 总预算 50ms < MIN_LLM_CALL_MS(100ms)：即使窗口刚开也不该发起 LLM 调用
        AgentBudget tiny = AgentBudget.start(50L, 30_000);
        assertThat(tiny.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)).isFalse();
        assertThat(tiny.allows(AgentBudget.MIN_RESEARCH_STEP_MS)).isFalse();
    }
}