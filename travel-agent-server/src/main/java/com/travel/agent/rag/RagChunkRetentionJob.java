package com.travel.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 知识库历史版本知识块的每日清理（batch10 / C）。
 *
 * <p>为什么要定时清：batch9 起内容变更只追加版本、不删旧块（可审计、可回滚），
 * 但反复更新同一篇文档会让 {@code rag_chunk} 无限增长——一张 20000 字的文档每改一次就多几十行
 * 向量文本，长期跑下来既占磁盘又拖慢全量装载。所以按
 * {@code travel.rag.chunk-version-retention}（默认 2）保留最近若干个历史版本，更老的块清掉。
 *
 * <p>为什么单独一个类而不是把 {@code @Scheduled} 挂在 {@link RagVersionService} 上：
 * <ul>
 *   <li>要单测的核心方法 {@link RagVersionService#purgeOldVersionChunks(int)} 是包可见的
 *       （Spring 的 {@code @Scheduled} 无法代理非 public 方法）；</li>
 *   <li>调度是"触发"，清理是"业务"，分开后测试只调业务方法，不依赖时钟。</li>
 * </ul>
 *
 * <p>时钟/时区：cron 用 JVM 默认时区解析，项目在 {@code TimeZoneConfig} 里把默认时区固定为
 * {@code travel.timezone}（默认 Asia/Shanghai），所以这里就是「每天本地 04:30」。
 * 保留窗口本身按版本序号计算（不看时间），因此服务时钟漂移不会改变清理结果。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagChunkRetentionJob {

    /** 每天 04:30（低峰期）；@Scheduled 要求编译期常量，故不抽成配置键 */
    static final String DAILY_CRON = "0 30 4 * * *";

    private final RagVersionService versionService;

    @Scheduled(cron = DAILY_CRON)
    public void purgeDaily() {
        try {
            RagVersionService.PurgeResult result = versionService.purgeOldVersionChunks();
            log.info("每日知识库历史版本块清理完成：清理版本 {} 个 / 删除知识块 {} 个",
                    result.versions(), result.chunks());
        } catch (RuntimeException e) {
            // 定时任务线程里异常只记账，不影响下一次触发；下一轮会重试同一批数据（清理是幂等的）
            log.warn("每日知识库历史版本块清理失败，等待下一次触发重试：{}", e.toString(), e);
        }
    }
}