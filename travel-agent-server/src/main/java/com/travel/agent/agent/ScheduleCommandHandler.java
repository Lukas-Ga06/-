package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ScheduleSnapshot;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「批量清空日程」（{@link IntentResult#CLEAR_SCHEDULE}）的方案生成。
 *
 * <p>清空不是直接落库：它和行程修改一样走确认门 —— 生成 N 个
 * {@link PlanOperation#REMOVE_SCHEDULE} 操作 → {@link ExecutorAgent#preview} 出 Diff →
 * 落 {@code pending_plan}（带行程/日程范围指纹）→ 用户确认后由 {@link ExecutorAgent#commit}
 * 在单事务内删除；路径上任何一步都不绕过 R1/R2。
 *
 * <p>范围必须由用户明说：没说就追问，绝不替用户猜一个默认日期（清空是破坏性操作）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduleCommandHandler {

    /** 单次清空的最大天数：范围更大时截断并如实写进 Diff（防御性上限，避免一次生成上千条操作） */
    static final int MAX_RANGE_DAYS = 31;

    private final ScheduleService scheduleService;
    private final ExecutorAgent executorAgent;
    private final PlanRevisionService planRevisionService;
    private final PendingPlanStore pendingPlans;

    /** 生成「清空某段日程」的待确认方案；无范围/无日程时直接答复，不产生 pending_plan */
    public AgentDtos.AgentResult clearSchedule(AgentContext ctx, IntentResult intent) {
        MessageParser.DateRange explicit = MessageParser.parseExplicitRange(ctx.getMessage(), LocalDate.now());
        if (explicit == null) {
            log.info("批量清空日程缺少日期范围，先追问: user={}", ctx.getUsername());
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("要清空哪段时间的日程？比如「清空下周一的日程」。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        MessageParser.DateRange range = clamp(explicit);
        List<ScheduleItem> schedules = scheduleService.findInRange(ctx.getUserId(), range.from(), range.to());
        if (schedules.isEmpty()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("这段时间没有日程，无需清空。")
                    .warnings(new ArrayList<>())
                    .build());
        }

        List<PlanOperation> operations = new ArrayList<>(schedules.size());
        for (ScheduleItem item : schedules) {
            operations.add(PlanOperation.builder()
                    .type(PlanOperation.REMOVE_SCHEDULE)
                    .targetScheduleId(item.getId())
                    .scheduleOriginal(ScheduleSnapshot.of(item))
                    .detail("清空日程：" + item.getDate() + " "
                            + (item.getTime() == null ? "" : item.getTime().toString().substring(0, 5) + " ")
                            + item.getTitle())
                    .build());
        }
        String rangeText = MessageParser.formatRange(range);
        String planId = UUID.randomUUID().toString();
        AgentPlan plan = AgentPlan.builder()
                .planId(planId)
                .intent(IntentResult.CLEAR_SCHEDULE)
                .tripId(null)
                .runId(ctx.getRunId())
                .operations(operations)
                .reasoning("清空 " + rangeText + " 的日程（" + operations.size() + " 项）")
                .focus(AgentDtos.AgentFocus.builder().type("schedule").build())
                .build();
        // 范围写进 extras：确认侧据此重算指纹，preview 据此描述实际（截断后的）范围
        plan.getExtras().put(PlanRevisionService.SCOPE_FROM, range.from().toString());
        plan.getExtras().put(PlanRevisionService.SCOPE_TO, range.to().toString());

        AgentDtos.AgentResult preview = executorAgent.preview(ctx, plan, null);
        preview.setMessage("将清空 " + rangeText + " 的 " + operations.size()
                + " 项日程，确认后生效，之后可以随时撤销。");
        String revision = planRevisionService.revision(plan, null, ctx.getUserId());
        // put 会回填 confirmation.contentHash（内容指纹），所以必须先 put 再发 confirmation 事件
        pendingPlans.put(new PendingPlanStore.PendingPlan(
                planId, ctx.getUserId(), null, plan, preview, revision, null, Instant.now()));

        if (preview.getDiff() != null) {
            ctx.emit("plan_update", Map.of("diff", preview.getDiff(), "budget", preview.getBudget()));
        }
        if (preview.getFocus() != null) {
            ctx.emit("focus", preview.getFocus());
        }
        if (preview.getConfirmation() != null) {
            ctx.emit("confirmation", preview.getConfirmation());
        }
        return complete(ctx, preview);
    }

    /** 超过 {@link #MAX_RANGE_DAYS} 天的范围截断到上限（含首日），由 Diff 文案如实呈现实际范围 */
    private static MessageParser.DateRange clamp(MessageParser.DateRange requested) {
        LocalDate lastDay = requested.from().plusDays(MAX_RANGE_DAYS - 1L);
        return requested.to().isAfter(lastDay)
                ? new MessageParser.DateRange(requested.from(), lastDay)
                : requested;
    }

    private AgentDtos.AgentResult complete(AgentContext ctx, AgentDtos.AgentResult result) {
        // 与 AssistantIntentHandler#complete 同一口径：SSE 的 complete 事件在 handler 里发，
        // 多意图提示必须在这里挂上（引擎 finish 处再挂靠前缀幂等）
        MultiIntentNotices.attach(ctx.getAlsoRequested(), result);
        ctx.emit("complete", result);
        return result;
    }
}