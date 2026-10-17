package com.travel.agent;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PendingPlanStore;
import com.travel.agent.agent.PlanOperation;
import com.travel.agent.agent.TripDraft;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.PendingPlanRecord;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.repository.PendingPlanRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 待确认方案落库存储：往返序列化、归属校验、过期清理。
// 覆盖「多实例 / 重启后仍能确认」这条此前不成立的能力。
class PendingPlanStoreTest extends IntegrationTestSupport {

    @Autowired
    private PendingPlanStore store;

    @Autowired
    private PendingPlanRepository repository;

    private static AgentPlan samplePlan(String planId, Long tripId) {
        return AgentPlan.builder()
                .planId(planId)
                .intent(IntentResult.MODIFY_ITINERARY)
                .tripId(tripId)
                .city(new CityCatalog("上海",
                        List.of(new Attraction("a1", "外滩", "outdoor", List.of("经典"), 31.2, 121.4, 0, 2, 4.8, "江景")),
                        List.of(), List.of()))
                .tripDraft(TripDraft.builder()
                        .name("上海三日游")
                        .destination("上海")
                        .startDate(LocalDate.of(2026, 5, 1))
                        .days(3)
                        .budget(BigDecimal.valueOf(3000))
                        .preferences(List.of("地铁"))
                        .build())
                .operations(List.of(PlanOperation.builder()
                        .type(PlanOperation.ADD)
                        .draft(ItineraryItem.builder()
                                .dayIndex(1)
                                .itemType(ItineraryItem.TYPE_ATTRACTION)
                                .title("外滩")
                                .cost(BigDecimal.valueOf(100))
                                .build())
                        .build()))
                .reasoning("测试方案")
                .focus(AgentDtos.AgentFocus.builder().type("itinerary").day(1).build())
                .extras(Map.of("total", BigDecimal.valueOf(3000)))
                .build();
    }

    private static AgentDtos.AgentResult samplePreview(String planId) {
        return AgentDtos.AgentResult.builder()
                .planId(planId)
                .status(AgentDtos.STATUS_NEED_CONFIRMATION)
                .message("待确认")
                .confirmation(AgentDtos.ConfirmationRequest.builder()
                        .planId(planId).summary("新增 1 项").riskLevel("MEDIUM").build())
                .warnings(List.of("注意时间冲突"))
                .build();
    }

    @Test
    @DisplayName("方案写入后可原样读回（跨请求 / 跨实例可用）")
    void roundTripsPlanThroughDatabase() throws Exception {
        Account account = registerNew();
        String planId = "plan-roundtrip-" + account.id();
        store.put(new PendingPlanStore.PendingPlan(planId, account.id(), null,
                samplePlan(planId, null), samplePreview(planId), null, Instant.now()));

        PendingPlanStore.PendingPlan loaded = store.peek(planId, account.id());
        assertThat(loaded).isNotNull();
        assertThat(loaded.userId()).isEqualTo(account.id());
        assertThat(loaded.plan().getIntent()).isEqualTo(IntentResult.MODIFY_ITINERARY);
        assertThat(loaded.plan().getTripDraft().getBudget()).isEqualByComparingTo(BigDecimal.valueOf(3000));
        assertThat(loaded.plan().getTripDraft().getStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(loaded.plan().getCity().name()).isEqualTo("上海");
        assertThat(loaded.plan().getOperations()).hasSize(1);
        assertThat(loaded.plan().getOperations().get(0).getDraft().getTitle()).isEqualTo("外滩");
        assertThat(loaded.preview().getConfirmation().getPlanId()).isEqualTo(planId);
        assertThat(loaded.preview().getWarnings()).containsExactly("注意时间冲突");

        assertThat(store.take(planId, account.id())).isNotNull();
        assertThat(repository.findById(planId)).isEmpty();
    }

    @Test
    @DisplayName("别人的方案既不能读也不能取走")
    void otherUserCannotAccessPlan() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        String planId = "plan-owner-" + owner.id();
        store.put(new PendingPlanStore.PendingPlan(planId, owner.id(), null,
                samplePlan(planId, null), samplePreview(planId), null, Instant.now()));

        assertThat(store.peek(planId, intruder.id())).isNull();
        assertThat(store.take(planId, intruder.id())).isNull();
        assertThat(repository.findById(planId)).isPresent();
        assertThat(store.peek(planId, owner.id())).isNotNull();
    }

    @Test
    @DisplayName("超过 TTL 的方案会被判为过期并清理")
    void expiredPlanIsRejectedAndPurged() throws Exception {
        Account account = registerNew();
        String planId = "plan-expired-" + account.id();
        repository.save(PendingPlanRecord.builder()
                .planId(planId)
                .userId(account.id())
                .planJson("{}")
                .previewJson("{}")
                .createdAt(LocalDateTime.now().minusMinutes(PendingPlanStore.TTL_MINUTES + 1))
                .build());

        assertThat(store.peek(planId, account.id())).isNull();
        assertThat(repository.findById(planId)).isEmpty();
    }

    @Test
    @DisplayName("purgeExpired 批量清理历史残留")
    void purgeRemovesOldRecords() {
        String oldPlanId = "plan-old-" + System.nanoTime();
        repository.save(PendingPlanRecord.builder()
                .planId(oldPlanId)
                .userId(1L)
                .planJson("{}")
                .previewJson("{}")
                .createdAt(LocalDateTime.now().minusHours(5))
                .build());

        assertThat(store.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(repository.findById(oldPlanId)).isEmpty();
    }
}
