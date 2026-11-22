package com.travel.agent;

import com.travel.agent.llm.LlmProperties;
import com.travel.agent.repository.LlmQuotaUsageRepository;
import com.travel.agent.service.LlmQuotaService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 配额闸门的并发正确性（P1-5）。
 *
 * 回归背景：旧实现是「读 used → 比较 limit → 自增」三段式（LlmQuotaService:41-48），
 * 方法级 @Transactional 挡不住读-判-写交叉：N 个并发请求同读 used=limit-1 会全部放行，
 * 当日实际调用可达 N 倍超发。顺序测试永远发现不了。
 *
 * 修复后闸门是单条条件自增 UPDATE（used_count &lt; limit 才 +1），按影响行数放行，
 * 因此并发下放行次数严格等于 limit。本用例用 10 个线程抢 limit=1。
 */
@TestPropertySource(properties = "travel.llm.quota-daily-limit=1")
class LlmQuotaConcurrencyTest extends IntegrationTestSupport {

    private static final int THREADS = 10;

    @Autowired
    private LlmQuotaService quotaService;

    @Autowired
    private LlmQuotaUsageRepository repository;

    @Autowired
    private LlmProperties llmProperties;

    @Test
    @DisplayName("10 线程并发抢 limit=1 的配额：恰好 1 次放行，计数恰好 1")
    void concurrentAcquireAllowsExactlyLimitCalls() throws Exception {
        assertThat(llmProperties.getQuotaDailyLimit()).as("属性应绑定 daily-limit=1").isEqualTo(1);
        String user = uniqueUsername();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    // 每次调用都是独立事务（service 上的 @Transactional 在线程内自行开启事务）
                    return quotaService.tryAcquire(user);
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed)
                    .as("limit=1 时并发放行次数必须恰好为 1（旧实现会放行多次）")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(quotaService.usedToday(user))
                .as("被拒绝的请求不应占用已用次数")
                .isEqualTo(1);
        assertThat(repository.findByUserNameAndUsageDate(user, LocalDate.now()))
                .as("同一用户同一天只应有一行计数")
                .isPresent();
        assertThat(repository.findByUserNameAndUsageDate(user, LocalDate.now()).orElseThrow().getUsedCount())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("顺序调用：limit=1 时第 2 次立即被拒，且计数不增长")
    void sequentialCallsAfterLimitAreRejected() {
        String user = uniqueUsername();
        assertThat(quotaService.tryAcquire(user)).isTrue();
        assertThat(quotaService.tryAcquire(user)).isFalse();
        assertThat(quotaService.tryAcquire(user)).isFalse();
        assertThat(quotaService.usedToday(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("不同用户互不影响（每个用户各自 limit=1）")
    void limitsArePerUser() {
        String a = uniqueUsername();
        String b = uniqueUsername();
        assertThat(quotaService.tryAcquire(a)).isTrue();
        assertThat(quotaService.tryAcquire(a)).isFalse();
        assertThat(quotaService.tryAcquire(b)).isTrue();
        assertThat(quotaService.usedToday(a)).isEqualTo(1);
        assertThat(quotaService.usedToday(b)).isEqualTo(1);
    }
}