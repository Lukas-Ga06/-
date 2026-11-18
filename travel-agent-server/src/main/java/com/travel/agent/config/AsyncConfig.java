package com.travel.agent.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.Map;

/**
 * Agent 专用线程池。
 *
 * 此前 SSE 与联网城市生成都在 CompletableFuture 的 ForkJoin commonPool 上跑，
 * 而它们是阻塞调用（大模型 5-30 秒、HTTP 请求），会挤占整个 JVM 共用的公共池。
 * 这里给出独立的有界池：队列满了直接拒绝，由调用方转成明确的错误提示。
 *
 * <p>TaskDecorator（V2.2 / FR-10）：把提交线程的 MDC（traceId）复制到池线程并在
 * 结束时清理——此前 {@code TraceIdFilter} 只在 servlet 线程写 MDC，Agent 运行期
 * 日志的 traceId 一直是空，无法把一次请求的链路日志关联起来。
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
        executor.setTaskDecorator(mdcPropagatingDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /** 复制提交线程的 MDC 上下文（traceId）到执行线程，执行完恢复原状（池线程复用不能残留） */
    static TaskDecorator mdcPropagatingDecorator() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }

}
