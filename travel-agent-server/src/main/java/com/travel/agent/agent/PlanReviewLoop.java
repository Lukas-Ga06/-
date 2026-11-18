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
 * 审核 → 有限重规划 → 再审的闭环（V2.2 FR-06），第十五批升级为任务图驱动（FR-02/FR-04）。
 *
 * <p>此前审核与修复都在同一个「REVIEW」任务体内内联执行，{@link AgentState#REPLAN}
 * 只靠 recordTask 留痕、任务之间没有依赖关系。现在每一步都是一张图上的节点：
 *
 * <pre>
 * REVIEW ──passed──▶ 结束
 *    │ !passed（有 HIGH 硬约束问题）
 *    ▼
 * 进入 REPLAN（发 replan 事件 + 派 REPLAN 任务，dependsOn=[REVIEW]）
 *    → 确定性修复（PlanRepairService）→ 派下一次 REVIEW（dependsOn=[REPLAN]）
 *    │ 修复动作非空且仍有 HIGH 问题 → 回收重派 REPLAN（replacesTaskId=上一轮，attempt+1，发 recover 事件）
 *    ▼
 * 仍不通过 → 返回 blocked 判定（确认门要求用户显式接受风险）
 * </pre>
 *
 * <p>「修复器是确定性的，同一份计划修两次还修不好，说明用户目标本身冲突」——上限仍是
 * {@value #MAX_ATTEMPTS} 次；继续循环只会空转。重派产生新任务行、旧行保留为证据，
 * 调用方只拿到最终一份结果。
 */
@Component
@RequiredArgsConstructor
public class PlanReviewLoop {

    /** 最多重规划几次：两次足够覆盖「天气换室内 → 超预算 → 精简」这类连锁修复 */
    public static final int MAX_ATTEMPTS = 2;

    private final ReflectAgent reflectAgent;
    private final PlanRepairService repairService;
    private final Coordinator coordinator;

    /**
     * 闭环结果：最终计划 / 最终判定 / 重规划次数 / 修复动作 / 最终 REVIEW 任务句柄。
     *
     * <p>{@code reviewTask} 是「最终那次审核」的任务节点——调用方重建 Diff 时要依赖它。
     */
    public record Outcome(AgentPlan plan, ReflectAgent.ReviewVerdict verdict, int attempts,
                          List<String> repairs, Coordinator.TaskHandle reviewTask) {
    }

    /**
     * 在入参（首轮 REVIEW 的结论与任务句柄）基础上继续闭环：REPLAN → 再审（≤{@value #MAX_ATTEMPTS} 次）。
     *
     * <p>只做计划层面的修复与再判定，不改库；调用方拿到最终 plan 后重新生成 Diff 预览。
     */
    public Outcome run(AgentContext ctx, AgentPlan plan, Trip trip,
                       Coordinator.TaskHandle reviewTask, ReflectAgent.ReviewVerdict initialVerdict) {
        List<String> repairs = new ArrayList<>();
        int attempts = 0;
        Coordinator.TaskHandle currentReview = reviewTask;
        Coordinator.TaskHandle lastReplan = null;
        ReflectAgent.ReviewVerdict verdict = initialVerdict;

        while (!verdict.passed() && attempts < MAX_ATTEMPTS) {
            attempts++;
            if (ctx != null) {
                ctx.setState(AgentState.REPLAN);
                ctx.emit("replan", Map.of("attempt", attempts, "issues", summaries(verdict)));
            }
            final int attemptNumber = attempts;
            // 本轮修复基于「进入本轮时」的计划快照；修复器是确定性的，同一份输入得到同一组动作
            final AgentPlan current = plan;
            Coordinator.TaskSpec spec = Coordinator.TaskSpec
                    .after(AgentTask.RECEIVER_REVIEWER, "REPLAN", currentReview)
                    .replacing(lastReplan, attempts - 1, "第 " + attempts + " 次重规划");
            // 首次是「派发」，第二次起是「回收重派」（发 recover 事件、替换上一轮 REPLAN 行）
            Coordinator.Receipt<PlanRepairService.Result> receipt = lastReplan == null
                    ? coordinator.dispatch(ctx, spec, () -> repairService.repair(current, trip),
                            summarizer(attemptNumber))
                    : coordinator.redispatch(ctx, spec, () -> repairService.repair(current, trip),
                            summarizer(attemptNumber));
            PlanRepairService.Result repaired = receipt.value();
            lastReplan = receipt.handle();
            repairs.addAll(repaired.actions());
            if (!repaired.changed()) {
                break;
            }
            plan = repaired.plan();
            // 修复后必须再审：REPLAN → REVIEW 的依赖边保证「没重规划完就不会有新一轮审核」
            final AgentPlan planned = plan;
            Coordinator.Receipt<ReflectAgent.ReviewVerdict> reReview = coordinator.dispatch(ctx,
                    Coordinator.TaskSpec.after(AgentTask.RECEIVER_REVIEWER, "REVIEW", lastReplan),
                    () -> reflectAgent.reviewVerdict(planned, trip),
                    v -> v.passed() ? "复审通过" : "复审未通过：" + v.highIssues().size() + " 项高风险");
            currentReview = reReview.handle();
            verdict = reReview.value();
        }
        return new Outcome(plan, verdict, attempts, repairs, currentReview);
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

    /** REPLAN 任务摘要：有修复动作写动作，没有就写「无可自动修复项」（≤1000 列宽） */
    private static java.util.function.Function<PlanRepairService.Result, String> summarizer(int attempt) {
        return result -> result.changed()
                ? "第 " + attempt + " 次重规划：" + truncate(String.join("；", result.actions()))
                : "第 " + attempt + " 次重规划：没有可自动修复项";
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