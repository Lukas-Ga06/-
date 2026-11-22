package com.travel.agent.config;

import com.travel.agent.entity.User;
import com.travel.agent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 初始化演示账号 demo / demo123（travel.demo-account.enabled=false 可关闭，生产部署建议关闭） */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** 生产部署设 DEMO_ACCOUNT=false 关闭公开弱密码账号 */
    @Value("${travel.demo-account.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("演示账号已禁用（travel.demo-account.enabled=false）");
            return;
        }
        if (!userRepository.existsByUsername("demo")) {
            userRepository.save(User.builder()
                    .username("demo")
                    .password(passwordEncoder.encode("demo123"))
                    .nickname("演示用户")
                    .build());
            log.info("已创建演示账号: demo / demo123");
        }
    }
}
