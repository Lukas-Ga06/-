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
    private final WeChat wechat = new WeChat();
    private final Webhook webhook = new Webhook();

    @Data
    public static class Telegram {

        /** BotFather 签发的 token；留空 = 渠道关闭。经环境变量注入，不落仓库 */
        private String botToken = "";

        /** 长轮询挂起秒数（Telegram 上限 50） */
        private long pollTimeoutSeconds = 25;
    }

    @Data
    public static class WeChat {

        /** 公众号服务器配置里填的 Token（接入验证 sha1(token+timestamp+nonce)）；留空 = 渠道关闭。明文模式 */
        private String token = "";

        /**
         * 客服消息（可选）：配置 appid + secret 后，回复改走「客服消息接口」异步发送——
         * 解除被动回复 5s 窗口限制（行程规划不再超窗丢失）。需已认证公众号；
         * 与 token 独立：只配 token 仍走同步被动回复
         */
        private String appid = "";
        private String secret = "";

        public boolean customerServiceEnabled() {
            return appid != null && !appid.isBlank() && secret != null && !secret.isBlank();
        }
    }

    @Data
    public static class Webhook {

        /** 显式开启后才暴露 /api/channels/webhook；密钥即凭证（X-Webhook-Secret 头） */
        private boolean enabled = false;

        private String secret = "";
    }
}
