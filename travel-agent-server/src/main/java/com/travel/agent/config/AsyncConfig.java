package com.travel.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 专用线程池。
 *
 * 此前 SSE 与联网城市生成都在 CompletableFuture 的 ForkJoin commonPool 上跑，
 * 而它们是阻塞调用（大模型 5-30 秒、HTTP 请求），会挤占整个 JVM 共用的公共池。
 * 这里给出独立的有界池：队列满了直接拒绝，由调用方转成明确的错误提示。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "agentTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("travel-agent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

}
