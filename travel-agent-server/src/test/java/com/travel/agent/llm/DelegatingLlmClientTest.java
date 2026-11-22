package com.travel.agent.llm;

import com.travel.agent.service.LlmQuotaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5 的落点：所有调用方注入的是 {@link DelegatingLlmClient}，在这里记录「最近一次真实调用是否成功」，
 * 既不改变既有降级行为，又能让 {@code /api/system/status} 的 {@code llmVerified} 如实反映现实。
 */
class DelegatingLlmClientTest {

    private final OpenAiCompatibleLlmClient openAiClient = mock(OpenAiCompatibleLlmClient.class);
    private final LlmQuotaService quotaService = mock(LlmQuotaService.class);
    private final LlmRuntimeStatus runtimeStatus = new LlmRuntimeStatus();
    private final DelegatingLlmClient client = new DelegatingLlmClient(
            openAiClient, mock(LlmProperties.class), quotaService, new SimpleMeterRegistry(), runtimeStatus);

    private static final List<LlmMessage> MESSAGES = List.of(LlmMessage.user("你好"));

    @Test
    void successMarksVerified() {
        when(openAiClient.available()).thenReturn(true);
        when(quotaService.tryAcquire(any())).thenReturn(true);
        when(openAiClient.chat(anyList(), anyDouble())).thenReturn("好的");

        assertThat(client.chat(MESSAGES, 0.3)).isEqualTo("好的");
        assertThat(runtimeStatus.verified()).isTrue();
    }

    @Test
    void modelFailureMarksUnverified() {
        when(openAiClient.available()).thenReturn(true);
        when(quotaService.tryAcquire(any())).thenReturn(true);
        when(openAiClient.chat(anyList(), anyDouble())).thenReturn(null);

        assertThat(client.chat(MESSAGES, 0.3)).isNull();
        assertThat(runtimeStatus.verified()).isFalse();
    }

    @Test
    void notConfiguredNeverClaimsVerified() {
        when(openAiClient.available()).thenReturn(false);

        assertThat(client.chat(MESSAGES, 0.3)).isNull();
        assertThat(runtimeStatus.verified()).isFalse();
        verify(openAiClient, never()).chat(anyList(), anyDouble());
    }

    @Test
    void quotaRejectionMarksUnverified() {
        when(openAiClient.available()).thenReturn(true);
        when(quotaService.tryAcquire(any())).thenReturn(false);

        assertThat(client.chat(MESSAGES, 0.3)).isNull();
        assertThat(runtimeStatus.verified()).isFalse();
        verify(openAiClient, never()).chat(anyList(), anyDouble());
    }

    @Test
    void timeoutOverloadTracksTheSameWay() {
        when(openAiClient.available()).thenReturn(true);
        when(quotaService.tryAcquire(any())).thenReturn(true);
        when(openAiClient.chat(anyList(), anyDouble(), anyInt())).thenReturn("润色后");

        assertThat(client.chat(MESSAGES, 0.3, 5000)).isEqualTo("润色后");
        assertThat(runtimeStatus.verified()).isTrue();

        when(openAiClient.chat(anyList(), anyDouble(), anyInt())).thenReturn(null);
        assertThat(client.chat(MESSAGES, 0.3, 5000)).isNull();
        assertThat(runtimeStatus.verified()).isFalse();
    }
}
