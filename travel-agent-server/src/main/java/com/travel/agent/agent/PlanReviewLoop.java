package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.AgentTask;
import com.travel.agent.entity.Trip;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审核 → 有限重规划 → 再审的闭环（V2.2 FR-06）。
 *
 * <p>此前 {@code ReflectAgent.reviewVerdict} 只能报「不通过」，引擎却把结论当警告放行，
 * {@link AgentState#REPLAN} 自始至终是死代码。本类把最后一段接上：
 *
 * <pre>
 * 审核(reviewVerdict) ──passed──&gt; 返回
 *    │ !passed（有 HIGH 硬约束问题）
 *    ▼
 * 进入 REPLAN（发 replan 事件 + 落 REPLAN 任务）→ 确定性修复（PlanRepairService）→ 再审
 *    │ 修复动作非空且仍有 HIGH 问题 → 最多再来一次（{@value #MAX_ATTEMPTS} 次上限）
 *    ▼
 * 仍不通过 → 返回 blocked 判定，交给确认门要求用户显式接受风险
 * </pre>
 *
 * <p>上限的意义：修复器是确定性的，同一份计划修两次还修不好，说明是用户目标本身冲突
 * （例如预算连最便宜的组合都装不下），继续循环只会空转。
 */
@Component
@RequiredArgsConstructor
public class PlanReviewLoop {

    /** 最多重规划几次：两次足够覆盖「天气换室内 → 超预算 → 精简」这类连锁修复 */
    public static final int MAX_ATTEMPTS = 2;

    private final ReflectAgent reflectAgent;
    private final PlanRepairService repairService;
    private final AgentTaskRecorder taskRecorder;

    public record Outcome(AgentPlan plan, ReflectAgent.ReviewVerdict verdict, int attempts, List<String> repairs) {
    }

    /**
     * 审核并在必要时重规划（最多 {@value #MAX_ATTEMPTS} 次）。
     *
     * <p>只做计划层面的修复与再判定，不改库；调用方拿到最终 plan 后重新生成 Diff 预览。
     */
    public Outcome run(AgentContext ctx, AgentPlan plan, Trip trip) {
        List<String> repairs = new ArrayList<>();
        int attempts = 0;
        ReflectAgent.ReviewVerdict verdict = reflectAgent.reviewVerdict(plan, trip);

        while (!verdict.passed() && attempts < MAX_ATTEMPTS) {
            attempts++;
            if (ctx != null) {
                ctx.setState(AgentState.REPLAN);
                ctx.emit("replan", Map.of("attempt", attempts, "issues", summaries(verdict)));
            }
            final int attempt = attempts;
            // 本轮修复基于「进入本轮时」的计划快照；修复器是确定性的，同一份输入得到同一组动作
            final AgentPlan current = plan;
            PlanRepairService.Result repaired = taskRecorder.around(ctx,
                    AgentTask.RECEIVER_REVIEWER, "REPLAN",
                    () -> repairService.repair(current, trip),
                    result -> result.changed()
                            ? "第 " + attempt + " 次重规划：" + truncate(String.join("；", result.actions()))
                            : "第 " + attempt + " 次重规划：没有可自动修复项");
            repairs.addAll(repaired.actions());
            if (!repaired.changed()) {
                break;
            }
            plan = repaired.plan();
            verdict = reflectAgent.reviewVerdict(plan, trip);
        }
        return new Outcome(plan, verdict, attempts, repairs);
    }

    /** 结构化判定 → 对外 DTO（SSE complete / 待确认方案预览 / GET plans/{id} 共用同一份判断） */
    public static AgentDtos.PlanReview toDto(Outcome outcome) {
        List<AgentDtos.PlanIssue> issues = outcome.verdict().issues().stream()
                .map(issue -> AgentDtos.PlanIssue.builder()
                        .type(issue.type())
                        .severity(issue.severity())
                        .message(issue.message())
                        .build())
                .toList();
        return AgentDtos.PlanReview.builder()
                .passed(outcome.verdict().passed())
                // 有未解决 HIGH 问题 = 确认门必须要求用户显式接受风险
                .blocked(!outcome.verdict().passed())
                .attempts(outcome.attempts())
                .issues(issues)
                .repairs(List.copyOf(outcome.repairs()))
                .build();
    }

    /** replan 事件的用户可读摘要：只取 HIGH 级问题，最多 3 条，避免 SSE 事件过大 */
    private static List<String> summaries(ReflectAgent.ReviewVerdict verdict) {
        List<String> high = verdict.highIssues().stream()
                .map(ReflectAgent.PlanIssue::message)
                .limit(3)
                .toList();
        return high.isEmpty() ? List.of("审核发现需要重排的问题") : high;
    }

    private static String truncate(String text) {
        return text.length() > 900 ? text.substring(0, 899) + "…" : text;
    }
}