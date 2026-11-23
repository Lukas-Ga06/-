package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-B 多意图提示：只在有未执行请求时追加一次，不能改动已有回复，也不能重复追加。
 * REST 与 SSE 都会经过 complete()/finish() 两处装配，这里钉死幂等语义。
 */
class MultiIntentNoticesTest {

    private static AgentDtos.AgentResult result(String message) {
        return AgentDtos.AgentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .status(AgentDtos.STATUS_COMPLETED)
                .message(message)
                .build();
    }

    @Test
    @DisplayName("没有未执行请求时不改动已有回复")
    void noNoticeWithoutAlsoRequested() {
        AgentDtos.AgentResult result = result("已为你规划上海三日游。");

        MultiIntentNotices.attach(List.of(), result);

        assertThat(result.getMessage()).isEqualTo("已为你规划上海三日游。");
    }

    @Test
    @DisplayName("有未执行请求时追加一次用户可见提示，原有回复内容保持不变")
    void appendsNoticeKeepingExistingReply() {
        AgentDtos.AgentResult result = result("已为你规划上海三日游。");

        MultiIntentNotices.attach(List.of("查一下天气", "订酒店"), result);

        assertThat(result.getMessage()).startsWith("已为你规划上海三日游。");
        assertThat(result.getMessage()).contains("另外你提到的「查一下天气」「订酒店」这次没有执行");
        assertThat(result.getMessage()).contains("请单独发一条");
    }

    @Test
    @DisplayName("complete 与 finish 两处都调用时只出现一次（幂等）")
    void attachIsIdempotent() {
        AgentDtos.AgentResult result = result("好的。");

        MultiIntentNotices.attach(List.of("查一下天气"), result);
        MultiIntentNotices.attach(List.of("查一下天气"), result);

        String message = result.getMessage();
        assertThat(message.split("另外你提到的", -1)).hasSize(2);
    }

    @Test
    @DisplayName("空值/全空白条目被过滤：没有有效请求时不追加提示")
    void blankItemsAreIgnored() {
        List<String> requests = new ArrayList<>();
        requests.add(" ");
        requests.add(null);
        AgentDtos.AgentResult result = result("好的。");

        MultiIntentNotices.attach(requests, result);

        assertThat(result.getMessage()).isEqualTo("好的。");
        assertThat(MultiIntentNotices.line(requests)).isNull();
    }

    @Test
    @DisplayName("回复为 null 时安全处理")
    void nullResultIsSafe() {
        MultiIntentNotices.attach(List.of("查一下天气"), null);
        // 不抛异常即通过
        assertThat(MultiIntentNotices.line(List.of())).isNull();
    }
}
