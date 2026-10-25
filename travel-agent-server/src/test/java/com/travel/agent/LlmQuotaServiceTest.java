package com.travel.agent;

import com.travel.agent.repository.LlmQuotaUsageRepository;
import com.travel.agent.security.UserContext;
import com.travel.agent.service.LlmQuotaService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LLM 每日配额：按用户隔离、按日隔离、超限拒绝、无用户上下文不限 */
@TestPropertySource(properties = "travel.llm.quota-daily-limit=2")
class LlmQuotaServiceTest extends IntegrationTestSupport {

    @Autowired
    private LlmQuotaService quotaService;

    @Autowired
    private LlmQuotaUsageRepository repository;

    @Autowired
    private com.travel.agent.llm.LlmProperties llmProperties;

    @AfterEach
    void cleanUp() {
        UserContext.clear();
    }

    @Test
    void perUserDailyLimitBlocksThirdCall() {
        org.junit.jupiter.api.Assertions.assertEquals(2, llmProperties.getQuotaDailyLimit(), "属性应绑定 daily-limit=2");
        String user = uniqueUsername();
        assertTrue(quotaService.tryAcquire(user));
        assertTrue(quotaService.tryAcquire(user));
        assertFalse(quotaService.tryAcquire(user), "第 3 次应被拒绝");
        assertEquals(2, quotaService.usedToday(user));
    }

    @Test
    void usersAreIndependent() {
        String a = uniqueUsername();
        String b = uniqueUsername();
        assertTrue(quotaService.tryAcquire(a));
        assertTrue(quotaService.tryAcquire(b), "另一用户不受影响");
        assertEquals(1, quotaService.usedToday(a));
        assertEquals(1, quotaService.usedToday(b));
    }

    @Test
    void blankUserIsUnlimited() {
        assertTrue(quotaService.tryAcquire(null));
        assertTrue(quotaService.tryAcquire(" "));
    }

    @Test
    void usageRowsAccumulatePerUser() {
        String user = uniqueUsername();
        quotaService.tryAcquire(user);
        quotaService.tryAcquire(user);
        var row = repository.findByUserNameAndUsageDate(user, LocalDate.now());
        assertTrue(row.isPresent());
        assertEquals(2, row.get().getUsedCount());
    }
}
