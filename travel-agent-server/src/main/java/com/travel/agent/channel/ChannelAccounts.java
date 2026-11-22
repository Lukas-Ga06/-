package com.travel.agent.channel;

import com.travel.agent.entity.User;
import com.travel.agent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 渠道账号开通：每个外部渠道身份（Telegram chat / 微信 openid）对应一个本地账号，
 * 首次消息时自动创建（密码随机且不外发——该账号只能经对应渠道使用，数据隔离与网页端一致）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChannelAccounts {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** 按渠道用户名（如 tg12345 / wx_oXXXX）取账号，不存在则自动创建 */
    public User resolveOrCreate(String channelUsername, String nickname) {
        return userRepository.findByUsername(channelUsername).orElseGet(() -> {
            String trimmed = nickname == null ? "" : (nickname.length() > 20 ? nickname.substring(0, 20) : nickname);
            User created = userRepository.save(User.builder()
                    .username(channelUsername)
                    .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .nickname(trimmed.isBlank() ? channelUsername : trimmed)
                    .build());
            log.info("渠道用户自动开通: {} -> {}", channelUsername, created.getId());
            return created;
        });
    }
}
