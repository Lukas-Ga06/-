package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多渠道入口配置：Telegram Bot（长轮询，纯出站连接）与通用 Webhook（自托管 bot 桥接）。
 * 两者都默认关闭；Telegram 配置 bot-token 即启用，Webhook 需显式 enabled + secret。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.channels")
public class ChannelProperties {

    private final Telegram telegram = new Telegram();
    private final Webhook webhook = new Webhook();

    @Data
    public static class Telegram {

        /** BotFather 签发的 token；留空 = 渠道关闭。经环境变量注入，不落仓库 */
        private String botToken = "";

        /** 长轮询挂起秒数（Telegram 上限 50） */
        private long pollTimeoutSeconds = 25;
    }

    @Data
    public static class Webhook {

        /** 显式开启后才暴露 /api/channels/webhook；密钥即凭证（X-Webhook-Secret 头） */
        private boolean enabled = false;

        private String secret = "";
    }
}
