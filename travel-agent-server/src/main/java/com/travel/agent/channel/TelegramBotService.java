package com.travel.agent.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.config.ChannelProperties;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Telegram 渠道入口：把 Bot 收到的消息交给同一个 AgentEngine 处理，再把结果发回聊天。
 * - 长轮询（getUpdates）：纯出站连接，本地/内网可运行，无需公网回调；
 * - 每个 Telegram chat 自动开通一个同名本地账号（tg{chatId}），数据隔离与网页端一致；
 * - 会话记忆按 chat 维持（conversationId=tg{chatId}）；
 * - 产生待确认方案时只播报方案，提示去网页端确认（本渠道不做确认操作）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelegramBotService implements SmartLifecycle {

    /** Telegram 单条消息上限 4096 字符，留余量 */
    private static final int MAX_MESSAGE_LENGTH = 3800;

    private final ChannelProperties properties;
    private final TelegramClient telegram;
    private final AgentEngine agentEngine;
    private final ChannelAccounts channelAccounts;

    private Thread worker;
    private volatile boolean running = false;

    @Override
    public void start() {
        String token = properties.getTelegram().getBotToken();
        if (token == null || token.isBlank()) {
            log.info("Telegram Bot 未配置 token（travel.channels.telegram.bot-token），渠道入口关闭");
            return;
        }
        running = true;
        worker = new Thread(this::pollLoop, "telegram-bot");
        worker.setDaemon(true);
        worker.start();
        log.info("Telegram Bot 渠道已启动（长轮询间隔 {}s）", properties.getTelegram().getPollTimeoutSeconds());
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        String token = properties.getTelegram().getBotToken();
        long offset = 0;
        while (running) {
            try {
                List<JsonNode> updates = telegram.getUpdates(token, offset,
                        (int) Math.min(Math.max(properties.getTelegram().getPollTimeoutSeconds(), 1), 50));
                for (JsonNode update : updates) {
                    offset = update.path("update_id").asLong(offset) + 1;
                    try {
                        handleUpdate(update);
                    } catch (Exception e) {
                        log.warn("处理 Telegram 消息失败: {}", e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Telegram 轮询异常，5s 后重试: {}", e.getMessage());
                sleepQuietly(5_000);
            }
        }
    }

    /** 单条 Telegram 更新 → 本地账号 → AgentEngine → 回复（供轮询循环与测试共用） */
    public void handleUpdate(JsonNode update) {
        JsonNode message = update.path("message");
        if (!message.isObject()) {
            return;
        }
        long chatId = message.path("chat").path("id").asLong(0);
        String text = message.path("text").asText("");
        if (chatId == 0 || text.isBlank()) {
            return; // 只处理文本消息（贴图/语音暂不支持）
        }
        String firstName = message.path("from").path("first_name").asText("旅友");
        User user = channelAccounts.resolveOrCreate("tg" + chatId, firstName);

        AgentContext ctx = new AgentContext();
        ctx.setUserId(user.getId());
        ctx.setUsername(user.getUsername());
        ctx.setMessage(text);
        ctx.setSessionId("tg-" + chatId + "-" + UUID.randomUUID());
        ctx.setConversationId(("tg" + chatId).length() <= 64 ? "tg" + chatId : "tg64");

        AgentDtos.AgentResult result = agentEngine.handle(ctx);
        String reply = result.getMessage() == null ? "已收到，但这次没有产生回复。" : result.getMessage();
        if (result.getPlanId() != null) {
            reply += "\n\n（检测到待确认的行程方案：请在网页端打开系统完成确认，本渠道暂不支持确认操作。）";
        }
        sendSplit(chatId, reply);
    }

    /** 每个 Telegram chat 对应一个本地账号（tg{chatId}），见 ChannelAccounts */
    private void sendSplit(long chatId, String reply) {
        String token = properties.getTelegram().getBotToken();
        for (int start = 0; start < reply.length(); start += MAX_MESSAGE_LENGTH) {
            String chunk = reply.substring(start, Math.min(reply.length(), start + MAX_MESSAGE_LENGTH));
            try {
                telegram.sendMessage(token, chatId, chunk);
            } catch (Exception e) {
                log.warn("Telegram 回复发送失败 chatId={}: {}", chatId, e.getMessage());
                return;
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
