package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.DailyReport;
import com.travel.agent.entity.HealthRecord;
import com.travel.agent.entity.Memo;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.service.DailyReportService;
import com.travel.agent.service.HealthService;
import com.travel.agent.service.MemoService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.util.Recurrence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人助理类意图的答复生成：备忘 / 日程 / 日报 / 健康。
 *
 * <p>从 {@link AgentEngine} 抽出，只负责「意图 → 直接执行 + 用户可读文案」；
 * 状态机、路由、取消与 SSE 事件仍由引擎控制。
 *
 * <p>这些意图会立即落库（记账在 {@link TravelQueryHandler}），因此票据只读会话在
 * 引擎路由前就被拦掉，不会进到本类。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantIntentHandler {

    private final MemoService memoService;
    private final ScheduleService scheduleService;
    private final DailyReportService dailyReportService;
    private final HealthService healthService;

    /** 备忘：添加（个人助理生活模块） */
    public AgentDtos.AgentResult addMemo(AgentContext ctx, IntentResult intent) {
        String content = MessageParser.parseMemoContent(ctx.getMessage());
        if (content == null || content.isBlank()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请告诉我备忘内容，比如「记一下明天办签证」。")
                    .build());
        }
        Memo memo = memoService.add(ctx.getUserId(), content);
        long open = memoService.openCount(ctx.getUserId());
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("📝 已添加备忘：「" + memo.getContent() + "」。当前待办 " + open + " 项。")
                .warnings(new ArrayList<>())
                .build());
    }

    /** 备忘：查看待办列表 */
    public AgentDtos.AgentResult queryMemo(AgentContext ctx, IntentResult intent) {
        List<Memo> memos = memoService.list(ctx.getUserId());
        List<Memo> open = memos.stream().filter(m -> !m.isDone()).toList();
        long doneCount = memos.size() - open.size();
        if (open.isEmpty()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message(doneCount > 0
                            ? "📝 待办都完成啦（另有 " + doneCount + " 项已完成）。说「记一下……」可以添加新备忘。"
                            : "📝 备忘录是空的，说「记一下明天办签证」即可添加。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        StringBuilder sb = new StringBuilder("📝 你的待办共 ").append(open.size()).append(" 项：");
        for (int i = 0; i < open.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(open.get(i).getContent());
        }
        if (doneCount > 0) {
            sb.append("\n（另有 ").append(doneCount).append(" 项已完成）");
        }
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(sb.toString())
                .warnings(new ArrayList<>())
                .build());
    }

    /** 日程：添加（办公自动化模块） */
    public AgentDtos.AgentResult addSchedule(AgentContext ctx, IntentResult intent) {
        MessageParser.ScheduleRequest request = MessageParser.parseScheduleRequest(ctx.getMessage());
        if (request == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请告诉我日程事项，比如「明天下午3点开评审会」。")
                    .build());
        }
        ScheduleItem item = scheduleService.add(ctx.getUserId(), request.title(),
                request.date(), request.time(), null, request.recurrence(), null);
        String timeText = item.getTime() == null ? "" : item.getTime().toString().substring(0, 5) + " ";
        String repeatText = Recurrence.isRecurring(request.recurrence())
                ? "（" + Recurrence.label(request.recurrence()) + "）" : "";
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("📅 已加入日程：" + item.getDate() + " " + timeText + "「" + item.getTitle() + "」"
                        + repeatText + "。")
                .warnings(new ArrayList<>())
                .build());
    }

    /** 日程：查看 */
    public AgentDtos.AgentResult querySchedule(AgentContext ctx, IntentResult intent) {
        List<ScheduleItem> upcoming = scheduleService.upcoming(ctx.getUserId());
        if (upcoming.isEmpty()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("📅 日程是空的，说「明天下午3点开评审会」即可添加。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        StringBuilder sb = new StringBuilder("📅 即将到来的日程：");
        for (ScheduleItem item : upcoming) {
            sb.append("\n· ").append(item.getDate()).append(" ")
                    .append(item.getTime() == null ? "" : item.getTime().toString().substring(0, 5) + " ")
                    .append(item.getTitle());
        }
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(sb.toString())
                .warnings(new ArrayList<>())
                .build());
    }

    /** 日报：汇总当日真实数据自动生成 */
    public AgentDtos.AgentResult dailyReport(AgentContext ctx, IntentResult intent) {
        DailyReport report = dailyReportService.generate(ctx.getUserId(), LocalDate.now());
        String brief = report.getContent().length() > 500
                ? report.getContent().substring(0, 500) + "……（完整日报已保存，可在办公模块查看）"
                : report.getContent();
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("📋 今日日报已生成：\n\n" + brief)
                .warnings(new ArrayList<>())
                .build());
    }

    /** 健康：最新数据 + 一周均值 + 建议 */
    public AgentDtos.AgentResult queryHealth(AgentContext ctx, IntentResult intent) {
        HealthRecord latest = healthService.latest(ctx.getUserId());
        double[] avg = healthService.weeklyAverages(ctx.getUserId());
        if (latest == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("暂无健康数据。可在「❤️ 健康」模块导入小米手环演示数据，或手动记录。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        String advice = healthAdviceText(latest, avg[0], avg[1]);
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("❤️ 最新健康数据（" + latest.getDate() + "）：步数 "
                        + (latest.getSteps() == null ? "-" : latest.getSteps())
                        + "，睡眠 " + (latest.getSleepHours() == null ? "-" : latest.getSleepHours() + " 小时")
                        + "，静息心率 " + (latest.getRestingHeartRate() == null ? "-" : latest.getRestingHeartRate())
                        + "。\n近 7 天均值：步数 " + (int) avg[0] + "，睡眠 " + String.format("%.1f", avg[1]) + " 小时。\n"
                        + advice)
                .warnings(new ArrayList<>())
                .build());
    }

    private String healthAdviceText(HealthRecord latest, double avgSteps, double avgSleep) {
        StringBuilder sb = new StringBuilder();
        if (avgSteps < 6000) {
            sb.append("步数偏少，建议每天增加 30 分钟快走。");
        } else {
            sb.append("步数表现不错，保持规律运动。");
        }
        if (avgSleep < 7) {
            sb.append("睡眠不足 7 小时，建议提前 30 分钟入睡。");
        } else {
            sb.append("睡眠时长健康。");
        }
        if (latest.getRestingHeartRate() != null && latest.getRestingHeartRate() > 90) {
            sb.append("静息心率偏高，注意休息。");
        }
        return sb.toString();
    }

    private AgentDtos.AgentResult complete(AgentContext ctx, AgentDtos.AgentResult result) {
        ctx.emit("complete", result);
        return result;
    }
}