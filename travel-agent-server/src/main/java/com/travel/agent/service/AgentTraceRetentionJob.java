package com.travel.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 追踪表的每日清理（V2.2 / FR-10）。
 *
 * <p>追踪表的价值在「最近发生了什么」：排障、审计、前端过程面板都只关心近期运行；
 * 保留 {@link AgentTraceService#RETENTION_DAYS}（默认 14 天）之前的 run/task/tool
 * 记录只会让三张表无限增长。沿用 {@code RagChunkRetentionJob} 的先例：
 * 调度（触发）与清理（业务）分开放，测试只调业务方法不依赖时钟。
 *
 * <p>时刻选 04:40：避开 04:30 已有的两个清理任务（会话消息 / 知识块），减少同刻并发写放大。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentTraceRetentionJob {

    /** 每天 04:40（低峰期，避开 04:30 的两个既有清理任务） */
    static final String DAILY_CRON = "0 40 4 * * *";

    private final AgentTraceService traceService;

    @Scheduled(cron = DAILY_CRON)
    public void purgeDaily() {
        try {
            traceService.purgeExpired();
        } catch (RuntimeException e) {
            // 定时任务线程里异常只记账，不影响下一次触发；清理是幂等的，下一轮重试同一批数据
            log.warn("每日 Agent 追踪清理失败，等待下一次触发重试：{}", e.toString(), e);
        }
    }
}
