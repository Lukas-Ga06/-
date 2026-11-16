package com.travel.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关。
 *
 * 项目此前没有任何 {@code @Scheduled}（也没有 {@code @EnableScheduling}），
 * 而会话消息的保留期需要一个「没人用也要跑」的兜底清理器（停更账号没人写入，
 * 只靠写入时顺手清理会让过期数据无限堆积），所以在这里统一开启调度。
 *
 * 为什么单独一个类而不是挂在 {@link AsyncConfig} 上：
 * AsyncConfig 只负责 `agentTaskExecutor` 线程池（异步执行），调度是另一件事；
 * 分开后想关掉定时任务（例如某些部署形态）时只动这一个类即可，不会连带影响异步链路。
 * 两者不冲突：`@EnableScheduling` 需要的是 {@code TaskScheduler}，
 * Spring Boot 会自动配一个；AsyncConfig 提供的 ThreadPoolTaskExecutor 是 Executor，互不干扰。
 */
@Configuration
@EnableScheduling
public class ConversationSchedulingConfig {
}