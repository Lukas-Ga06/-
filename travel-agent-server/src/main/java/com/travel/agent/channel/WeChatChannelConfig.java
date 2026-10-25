package com.travel.agent.channel;

import com.travel.agent.config.ChannelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 微信客服消息客户端：仅当配置了 appid（secret 同时必填，运行时校验）时装配 */
@Configuration
@RequiredArgsConstructor
public class WeChatChannelConfig {

    private final ChannelProperties properties;

    @Bean
    @ConditionalOnProperty(prefix = "travel.channels.wechat", name = "appid")
    public WeChatClient weChatClient() {
        var wechat = properties.getWechat();
        return new WeChatClient(wechat.getAppid(), wechat.getSecret());
    }
}
