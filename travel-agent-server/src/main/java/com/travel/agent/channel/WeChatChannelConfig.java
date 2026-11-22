package com.travel.agent.channel;

import com.travel.agent.config.ChannelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 微信客服消息客户端：仅当配置了 appid（secret 同时必填，运行时校验）时装配 */
@Configuration
@RequiredArgsConstructor
public class WeChatChannelConfig {

    private final ChannelProperties properties;

    @Bean
    // 必须用非空表达式而非 @ConditionalOnProperty(name="appid")：yml 给了空默认值
    // appid: ${WECHAT_OA_APPID:}，属性以空串存在时 @ConditionalOnProperty 也会匹配，
    // 导致未配置 appid 仍装配客户端、被动回复被误切成异步 ack（同步 XML 回复失效）。
    @ConditionalOnExpression("'${travel.channels.wechat.appid:}' != '' and '${travel.channels.wechat.secret:}' != ''")
    public WeChatClient weChatClient() {
        var wechat = properties.getWechat();
        return new WeChatClient(wechat.getAppid(), wechat.getSecret());
    }
}
