package com.travel.agent.channel;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.config.ChannelProperties;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

/**
 * 微信公众号渠道（明文模式）：接入验证 + 消息被动回复，复用同一个 AgentEngine。
 *
 * - GET：微信服务器握手，sha1(sort(token, timestamp, nonce)) == signature 才原样返回 echostr；
 * - POST：XML 文本消息 → AgentEngine → 被动回复 XML；
 * - 被动回复窗口 5s：快路径（意图识别/备忘/日程）可实时回复；行程规划可能超窗被微信判超时，
 *   验证号/未认证号无法使用客服消息接口，正式环境建议接入客服消息做异步回复（见 README）；
 * - token 未配置时端点始终 403（渠道关闭）。
 */
@RestController
@RequestMapping("/api/channels/wechat")
@RequiredArgsConstructor
@Slf4j
public class WeChatOfficialAccountController {

    private final ChannelProperties properties;
    private final AgentEngine agentEngine;
    private final ChannelAccounts channelAccounts;
    /** 配置了 appid+secret 时注入客服消息客户端（WeChatChannelConfig），异步回复解除 5s 窗口 */
    private final org.springframework.beans.factory.ObjectProvider<WeChatClient> weChatClient;

    /** 微信签名时戳允许的偏差（秒）：POST 与 GET 共用，限制重放窗口 */
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300;

    private volatile java.util.concurrent.ExecutorService replyExecutor;

    /** 单线程异步回复：微信场景低频，一个守护线程足够 */
    private java.util.concurrent.ExecutorService executor() {
        if (replyExecutor == null) {
            synchronized (this) {
                if (replyExecutor == null) {
                    replyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread thread = new Thread(r, "wechat-reply");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
            }
        }
        return replyExecutor;
    }

    /** 微信服务器接入验证（GET） */
    @GetMapping
    public org.springframework.http.ResponseEntity<String> verify(
            @RequestParam(required = false) String signature,
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String nonce,
            @RequestParam(required = false) String echostr) {
        String token = properties.getWechat().getToken();
        if (token == null || token.isBlank()) {
            return org.springframework.http.ResponseEntity.status(403).body("wechat channel disabled");
        }
        if (isBlank(signature) || isBlank(timestamp) || isBlank(nonce) || isBlank(echostr)) {
            return org.springframework.http.ResponseEntity.badRequest().body("missing params");
        }
        // 恒时比较：避免按响应时间差逐字节猜签名
        return constantTimeEquals(sign(token, timestamp, nonce), signature)
                ? org.springframework.http.ResponseEntity.ok(echostr)
                : org.springframework.http.ResponseEntity.status(403).body("signature mismatch");
    }

    /** 微信消息推送（POST XML）：text 消息交给 AgentEngine；event 转欢迎语；其余忽略 */
    @PostMapping
    public org.springframework.http.ResponseEntity<String> receive(
            @RequestParam(required = false) String signature,
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String nonce,
            @RequestBody(required = false) String body) {
        String token = properties.getWechat().getToken();
        if (token == null || token.isBlank()) {
            return org.springframework.http.ResponseEntity.status(403).body("");
        }
        // 与 GET 握手同一套验签：微信真实推送一定带这三个参数（URL 不变，仍 /api/channels/wechat）。
        // 修复前 POST 完全不验签，只要渠道启用，任何人伪造 XML 即可匿名建号、驱动 AgentEngine
        //（消耗模型额度）并写入 pending_plan 污染数据。
        if (isBlank(signature) || isBlank(timestamp) || isBlank(nonce)) {
            return org.springframework.http.ResponseEntity.status(403).body("signature required");
        }
        if (!constantTimeEquals(sign(token, timestamp, nonce), signature)) {
            return org.springframework.http.ResponseEntity.status(403).body("signature mismatch");
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return org.springframework.http.ResponseEntity.status(403).body("timestamp expired");
        }
        // ±5 分钟新鲜度：限定重放窗口（微信重试通常在分钟级内）
        if (Math.abs(System.currentTimeMillis() / 1000 - sentAt) > TIMESTAMP_TOLERANCE_SECONDS) {
            return org.springframework.http.ResponseEntity.status(403).body("timestamp expired");
        }
        if (isBlank(body)) {
            return org.springframework.http.ResponseEntity.ok("");
        }
        java.util.Map<String, String> xml;
        try {
            xml = parseXml(body);
        } catch (Exception e) {
            log.warn("微信消息 XML 解析失败: {}", e.getMessage());
            return org.springframework.http.ResponseEntity.ok("");
        }
        String msgType = xml.getOrDefault("MsgType", "");
        String fromUser = xml.getOrDefault("FromUserName", "");
        String toUser = xml.getOrDefault("ToUserName", "");
        if (fromUser.isBlank()) {
            return org.springframework.http.ResponseEntity.ok("");
        }
        boolean isText = "text".equals(msgType);
        boolean isSubscribe = "event".equals(msgType) && "subscribe".equals(xml.getOrDefault("Event", ""));
        if (!isText && !isSubscribe) {
            return org.springframework.http.ResponseEntity.ok("");
        }
        String incoming = isText ? xml.getOrDefault("Content", "").trim() : "你好";
        if (isText && "/start".equalsIgnoreCase(incoming)) {
            incoming = "你好";
        }
        if (isText && incoming.isBlank()) {
            return org.springframework.http.ResponseEntity.ok("");
        }

        String incomingFinal = incoming;
        String fromUserFinal = fromUser;
        // 配置了 appid/secret → 客服消息异步回复：立即 ack，解除被动回复 5s 窗口限制
        WeChatClient customerService = weChatClient.getIfAvailable();
        if (customerService != null) {
            executor().submit(() -> {
                try {
                    String reply = handle(incomingFinal, fromUserFinal);
                    if (!reply.isBlank()) {
                        customerService.sendText(fromUserFinal, reply);
                    }
                } catch (Exception e) {
                    log.warn("微信异步回复失败 openid={}: {}", fromUserFinal, e.getMessage());
                }
            });
            return org.springframework.http.ResponseEntity.ok("success");
        }

        // 未配置客服接口 → 同步被动回复（5s 窗口内）
        String reply = handle(incoming, fromUser);
        if (reply.isBlank()) {
            return org.springframework.http.ResponseEntity.ok("");
        }
        String responseXml = "<xml><ToUserName><![CDATA[" + fromUser + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + toUser + "]]></FromUserName>"
                + "<CreateTime>" + (System.currentTimeMillis() / 1000) + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + reply + "]]></Content></xml>";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
                .body(responseXml);
    }

    private String handle(String text, String openid) {
        User user = channelAccounts.resolveOrCreate("wx_" + openid, "微信旅友");
        AgentContext ctx = new AgentContext();
        ctx.setUserId(user.getId());
        ctx.setUsername(user.getUsername());
        ctx.setMessage(text);
        ctx.setSessionId("wx-" + UUID.randomUUID());
        // 会话记忆按 openid 维持（截到 64 内，符合 conversationId 校验）
        ctx.setConversationId(("wx" + openid).length() <= 64 ? "wx" + openid : "wx64");
        AgentDtos.AgentResult result = agentEngine.handle(ctx);
        String reply = result.getMessage() == null ? "已收到，但这次没有产生回复。" : result.getMessage();
        if (result.getPlanId() != null) {
            reply += "\n\n（检测到待确认的行程方案：请在网页端打开系统完成确认，本渠道暂不支持确认操作。）";
        }
        return reply.length() > 2000 ? reply.substring(0, 2000) : reply;
    }

    /** sha1(sort(token, timestamp, nonce))，微信接入验证算法 */
    static String sign(String token, String timestamp, String nonce) {
        ArrayList<String> parts = new ArrayList<>(java.util.List.of(token, timestamp, nonce));
        java.util.Collections.sort(parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }

    /** 防XXE 的最小 XML 解析：只取一级子元素的文本值 */
    private static java.util.Map<String, String> parseXml(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(body)));
        java.util.Map<String, String> map = new java.util.HashMap<>();
        var nodes = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                map.put(node.getNodeName(), node.getTextContent() == null ? "" : node.getTextContent().trim());
            }
        }
        return map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 恒时比较签名，避免按响应时间差逐字节猜测 */
    private static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
