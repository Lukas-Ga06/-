package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEvent;
import com.travel.agent.agent.Coordinator;
import com.travel.agent.entity.AgentRun;
import com.travel.agent.entity.AgentTask;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 第十五批 FR-02 / FR-04：Coordinator 的依赖、冲突与回收重派语义（确定性编排）。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>前置未完成 → 拒绝启动：body 不执行，落一条 CONFLICT 任务，发 conflict 事件；</li>
 *   <li>重派总是新行：attempt+1、replacesTaskId=旧行，旧行保持失败原值，发 recover 事件；</li>
 *   <li>同一逻辑工作只有一份结果：调用方只拿到新行的结果，旧行不可能是 SUCCEEDED。</li>
 * </ol>
 */
class CoordinatorConflictTest extends IntegrationTestSupport {

    @Autowired
    private Coordinator coordinator;

    @Autowired
    private AgentTraceService traceService;

    /** 建一个带真实 run 的上下文：任务行要能按 runId 回查才有意义 */
    private AgentContext contextWithRun(Account account) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        AgentRun run = traceService.startRun(account.id(), null, "任务图冲突测试");
        assertThat(run).isNotNull();
        ctx.setRunId(run.getRunId());
        return ctx;
    }

    private List<AgentTask> tasks(AgentContext ctx) {
        return traceService.tasksOf(ctx.getRunId());
    }

    @Test
    @DisplayName("前置失败 → 拒绝启动并记 CONFLICT；回收重派产生新行且只拿到一份结果")
    void failedPrerequisiteConflictsThenRedispatchYieldsSingleResult() throws Exception {
        Account account = registerNew();
        AgentContext ctx = contextWithRun(account);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ctx.addListener(events::add);

        // 1) 首次任务失败：FAILED 行保留，供后续依赖判断
        try {
            coordinator.dispatch(ctx, Coordinator.TaskSpec.of("planner-agent", "PLAN"),
                    () -> {
                        throw new IllegalStateException("规划失败");
                    },
                    value -> "不该成功的摘要");
            fail("首次派发的失败应原样抛出");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).isEqualTo("规划失败");
        }
        AgentTask failed = tasks(ctx).get(0);
        assertThat(failed.getStatus()).isEqualTo(AgentTask.STATUS_FAILED);
        assertThat(failed.getAttempt()).isZero();
        Coordinator.TaskHandle failedHandle = new Coordinator.TaskHandle(
                failed.getTaskId(), "planner-agent", "PLAN");

        // 2) 依赖失败前置 → 冲突：body 不执行，只留 CONFLICT 证据行
        assertThatThrownBy(() -> coordinator.dispatch(ctx,
                Coordinator.TaskSpec.after("executor-agent", "PREVIEW", failedHandle),
                () -> {
                    throw new AssertionError("前置未完成时 body 绝不能执行");
                },
                value -> "x"))
                .isInstanceOf(Coordinator.TaskConflictException.class)
                .satisfies(error -> assertThat(((Coordinator.TaskConflictException) error)
                        .conflict().missingDeps()).containsExactly(failed.getTaskId()));

        AgentTask conflict = tasks(ctx).stream()
                .filter(task -> AgentTask.STATUS_CONFLICT.equals(task.getStatus()))
                .findFirst().orElseThrow();
        assertThat(conflict.getTaskType()).isEqualTo("PREVIEW");
        assertThat(conflict.getDependsOn()).isEqualTo(failed.getTaskId());
        assertThat(tasks(ctx)).noneMatch(task -> "PREVIEW".equals(task.getTaskType())
                && AgentTask.STATUS_RUNNING.equals(task.getStatus()));

        // 3) 回收重派：同一逻辑任务产生新行，旧行保持 FAILED，调用方只拿到一份结果
        Coordinator.TaskSpec retry = Coordinator.TaskSpec.of("planner-agent", "PLAN")
                .replacing(failedHandle, 1, "规划失败后重试");
        Coordinator.Receipt<String> receipt = coordinator.redispatch(ctx, retry,
                () -> "唯一结果", value -> "重试成功");
        assertThat(receipt.value()).isEqualTo("唯一结果");
        assertThat(receipt.handle().taskId()).isNotEqualTo(failed.getTaskId());

        List<AgentTask> after = tasks(ctx);
        AgentTask retried = after.stream()
                .filter(task -> "PLAN".equals(task.getTaskType())
                        && AgentTask.STATUS_SUCCEEDED.equals(task.getStatus()))
                .findFirst().orElseThrow();
        assertThat(retried.getAttempt()).isEqualTo(1);
        assertThat(retried.getReplacesTaskId()).isEqualTo(failed.getTaskId());
        assertThat(after.stream().filter(task -> AgentTask.STATUS_SUCCEEDED.equals(task.getStatus())).count())
                .as("重派后只有一份成功结果")
                .isEqualTo(1);

        // 4) 三类事件都可见：delegate（派发）、conflict（拒绝启动）、recover（回收重派）
        List<String> eventNames = events.stream().map(AgentEvent::event).toList();
        assertThat(eventNames).contains("delegate", "conflict", "recover");
        assertThat(events).filteredOn(event -> "recover".equals(event.event()))
                .anySatisfy(event -> {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> payload = (java.util.Map<String, Object>) event.data();
                    assertThat(payload.get("replacesTaskId")).isEqualTo(failed.getTaskId());
                    assertThat(payload.get("attempt")).isEqualTo(1);
                    assertThat(payload.get("taskType")).isEqualTo("PLAN");
                });
    }

    @Test
    @DisplayName("依赖不存在的前置任务 → 同样拒绝启动（不静默放行）")
    void unknownDependencyIsRefused() throws Exception {
        Account account = registerNew();
        AgentContext ctx = contextWithRun(account);
        Coordinator.TaskHandle missing = new Coordinator.TaskHandle(
                "task-does-not-exist", "planner-agent", "PLAN");

        assertThatThrownBy(() -> coordinator.dispatch(ctx,
                Coordinator.TaskSpec.after("reflect-agent", "REVIEW", missing),
                () -> "不应执行", value -> "x"))
                .isInstanceOf(Coordinator.TaskConflictException.class);

        assertThat(tasks(ctx)).anyMatch(task -> AgentTask.STATUS_CONFLICT.equals(task.getStatus()));
        // 只允许留下一条冲突证据行：没有真正执行过（不存在 RUNNING/SUCCEEDED 的 REVIEW）
        assertThat(tasks(ctx)).noneMatch(task -> "REVIEW".equals(task.getTaskType())
                && !AgentTask.STATUS_CONFLICT.equals(task.getStatus()));
    }

    @Test
    @DisplayName("并行组真并行：两个 body 互相在屏障处等待对方开始，串行执行必然超时")
    void dispatchAllRunsBodiesConcurrently() throws Exception {
        Account account = registerNew();
        AgentContext ctx = contextWithRun(account);

        // 两个 body 都必须在屏障处等到对方；如果 dispatchAll 串行执行，第一项永远等不到
        // 第二项，await 超时后抛异常——这是「真并行」的确定性证明，不依赖时间戳精度。
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.function.Supplier<String> body = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("并行未发生（屏障等待失败）: " + e, e);
            }
            return "ok";
        };

        List<Coordinator.Receipt<?>> receipts = coordinator.dispatchAll(ctx, List.of(
                new Coordinator.ParallelTask<>(
                        Coordinator.TaskSpec.of("executor-agent", "PREVIEW"), body, value -> "预览完成"),
                new Coordinator.ParallelTask<>(
                        Coordinator.TaskSpec.of("reflect-agent", "REVIEW"), body, value -> "审核完成")));

        // 回执按声明顺序返回，两行都成功（异常会被 dispatchAll 原样抛出，跑到这里即证明都没超时）
        assertThat(receipts).hasSize(2);
        assertThat(receipts.get(0).value()).isEqualTo("ok");
        assertThat(receipts.get(1).value()).isEqualTo("ok");
        assertThat(receipts.get(0).handle().receiver()).isEqualTo("executor-agent");
        assertThat(receipts.get(1).handle().receiver()).isEqualTo("reflect-agent");

        List<AgentTask> rows = tasks(ctx);
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(task -> AgentTask.STATUS_SUCCEEDED.equals(task.getStatus()));
        // 两行都在 body 执行前创建：创建时间不晚于任何一方的结束时间
        assertThat(rows.get(0).getStartedAt()).isBeforeOrEqualTo(rows.get(1).getEndedAt());
        assertThat(rows.get(1).getStartedAt()).isBeforeOrEqualTo(rows.get(0).getEndedAt());
    }

    @Test
    @DisplayName("追踪不可用（无 runId）时退化为直接执行，行为与升级前一致")
    void withoutRunFallsBackToDirectExecution() throws Exception {
        registerNew();
        AgentContext ctx = new AgentContext();
        String value = coordinator.dispatch(ctx, Coordinator.TaskSpec.of("planner-agent", "PLAN"),
                () -> "直接执行", summary -> summary).value();
        assertThat(value).isEqualTo("直接执行");
    }
}