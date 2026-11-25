package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEvent;
import com.travel.agent.agent.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0：事件序号与阶段 sink 的纯单元语义（不启动 Spring）。
 *
 * <p>覆盖「seq 从 1 开始单调」「两参构造器兼容（seq=0）」「持久化 sink 抛异常不影响实时流」
 * 「setState 每次推进都通知 stage sink」。这些是 SSE 帧 id / 续传游标 / 状态机落库的基础契约。
 */
class AgentRunEventSeqTest {

    @Test
    @DisplayName("两参构造器保留兼容：seq=0（旧调用方帧上不带 id）")
    void legacyTwoArgConstructorHasZeroSeq() {
        AgentEvent event = new AgentEvent("start", Map.of("sessionId", "s"));
        assertThat(event.seq()).isZero();
        assertThat(event.event()).isEqualTo("start");
    }

    @Test
    @DisplayName("emit 分配 run 内从 1 开始的单调序号，listener 与 durable sink 看到同一份事件")
    void emitAssignsMonotonicSeqAndSinkSeesTheSameEvents() {
        AgentContext ctx = new AgentContext();
        List<AgentEvent> delivered = new CopyOnWriteArrayList<>();
        List<AgentEvent> persisted = new CopyOnWriteArrayList<>();
        ctx.addListener(delivered::add);
        ctx.setDurableEventSink(persisted::add);

        ctx.emit("start", Map.of("sessionId", "s"));
        ctx.emit("intent", Map.of("intent", "CHITCHAT"));
        ctx.emit("complete", Map.of("status", "COMPLETED"));

        assertThat(delivered).extracting(AgentEvent::seq).containsExactly(1L, 2L, 3L);
        assertThat(persisted).extracting(AgentEvent::seq).containsExactly(1L, 2L, 3L);
        assertThat(persisted).extracting(AgentEvent::event)
                .containsExactly("start", "intent", "complete");
    }

    @Test
    @DisplayName("持久化 sink 抛异常被吞掉：实时 listener 照常收到，后续事件继续分配 seq")
    void sinkFailureDoesNotBreakTheLiveStream() {
        AgentContext ctx = new AgentContext();
        List<AgentEvent> delivered = new ArrayList<>();
        ctx.addListener(delivered::add);
        AtomicInteger sinkCalls = new AtomicInteger();
        ctx.setDurableEventSink(event -> {
            sinkCalls.incrementAndGet();
            throw new IllegalStateException("db down");
        });

        ctx.emit("start", Map.of());
        ctx.emit("complete", Map.of("status", "COMPLETED"));

        assertThat(sinkCalls.get()).isEqualTo(2);
        assertThat(delivered).extracting(AgentEvent::seq).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("setState 每次推进都通知 stage sink；未设置 sink 时行为与升级前一致")
    void stageSinkReceivesEveryTransition() {
        AgentContext ctx = new AgentContext();
        List<String> stages = new CopyOnWriteArrayList<>();
        ctx.setStageSink(stages::add);

        ctx.setState(AgentState.UNDERSTAND);
        ctx.setState(AgentState.PLAN);
        ctx.setState(AgentState.ERROR);

        assertThat(stages).containsExactly("UNDERSTAND", "PLAN", "ERROR");
        assertThat(ctx.getState()).isEqualTo(AgentState.ERROR);

        AgentContext withoutSink = new AgentContext();
        withoutSink.setState(AgentState.PLAN);
        assertThat(withoutSink.getState()).isEqualTo(AgentState.PLAN);
    }
}
