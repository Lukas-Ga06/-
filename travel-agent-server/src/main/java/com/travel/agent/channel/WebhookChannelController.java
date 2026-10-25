package com.travel.agent.channel;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.config.ChannelProperties;
import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.User;
import com.travel.agent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * 通用 Webhook 渠道入口：自托管 bot（企业微信/钉钉/自定义脚本等）以固定密钥调用，
 * 消息交给与网页端相同的 AgentEngine，返回 {reply}。
 * 安全模型：端点 permitAll，密钥（X-Webhook-Secret，恒时比较）即唯一凭证，未配置密钥时端点始终拒绝。
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
@Slf4j
public class WebhookChannelController {

    private final ChannelProperties properties;
    private final AgentEngine agentEngine;
    private final UserRepository userRepository;

    /** 请求体：以哪个本地账号的身份处理这条消息（账号需已存在，不自动注册） */
    public record WebhookRequest(String username, String text) { }

    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<Map<String, String>>> handle(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody(required = false) WebhookRequest request) {
        String configured = properties.getWebhook().getSecret();
        if (!properties.getWebhook().isEnabled()
                || configured == null || configured.isBlank()
                || secret == null || secret.isBlank()
                || !MessageDigest.isEqual(
                        configured.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "渠道密钥无效或 Webhook 渠道未启用"));
        }
        if (request == null || isBlank(request.username()) || isBlank(request.text())) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "请求需包含 username 与 text 字段"));
        }
        User user = userRepository.findByUsername(request.username().trim()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "本地账号不存在，请先在网页端注册"));
        }

        AgentContext ctx = new AgentContext();
        ctx.setUserId(user.getId());
        ctx.setUsername(user.getUsername());
        ctx.setMessage(request.text().trim());
        ctx.setSessionId("webhook-" + UUID.randomUUID());
        // 会话记忆按账号维度维持（同账号多渠道共享历史，便于上下文衔接）
        ctx.setConversationId(sanitizeConversationId("wh_" + user.getUsername()));

        log.info("Webhook 渠道消息: user={} len={}", user.getUsername(), request.text().length());
        com.travel.agent.dto.AgentDtos.AgentResult result = agentEngine.handle(ctx);
        String reply = result.getMessage() == null ? "" : result.getMessage();
        if (result.getPlanId() != null) {
            reply += "\n\n（检测到待确认的行程方案：请在网页端完成确认，本渠道暂不支持确认操作。）";
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("reply", reply)));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 会话标识约束：[A-Za-z0-9_-] 且 ≤64；非法字符压成 '_' */
    private static String sanitizeConversationId(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9_-]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
