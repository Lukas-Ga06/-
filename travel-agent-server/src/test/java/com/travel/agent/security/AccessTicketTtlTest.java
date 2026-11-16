package com.travel.agent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票据 TTL 可配：默认 300 秒（URL 凭证宁短勿长），
 * 但部署方可以通过 travel.jwt.ticket-ttl-seconds 覆盖（例如内部工具链需要稍长的流）。
 *
 * <p>纯单测，不起 Spring 上下文：直接构造服务，验证构造参数真的生效。
 */
class AccessTicketTtlTest {

    /** 与 application-test.yml 相同的测试密钥（Base64，解码后 48 字节） */
    private static final String SECRET =
            "zH5p+FxWgj8rMvkAbh49f7lG7jON+493C+/qTaxuJ4I92t+NNB0QA+fdyB4VQYf9";

    private final JwtService jwtService = new JwtService(SECRET, 1);

    @Test
    void ttlIsConfigurable() {
        AccessTicketService service = new AccessTicketService(jwtService, 120);

        AccessTicketService.Ticket ticket = service.issue("alice", AccessTicketService.PURPOSE_AGENT_STREAM);

        assertThat(ticket.expiresInSeconds()).isEqualTo(120L);
        assertThat(ticket.purpose()).isEqualTo(AccessTicketService.PURPOSE_AGENT_STREAM);
        assertThat(service.validate(ticket.ticket(), AccessTicketService.PURPOSE_AGENT_STREAM)).isEqualTo("alice");
        // 用途隔离：SSE 票据不能当照片票据用
        assertThat(service.validate(ticket.ticket(), AccessTicketService.PURPOSE_MEDIA_READ)).isNull();
    }

    @Test
    void nonPositiveTtlFallsBackToDefault() {
        AccessTicketService service = new AccessTicketService(jwtService, 0);

        assertThat(service.issue("alice").expiresInSeconds())
                .isEqualTo(AccessTicketService.DEFAULT_TTL_SECONDS);
    }
}