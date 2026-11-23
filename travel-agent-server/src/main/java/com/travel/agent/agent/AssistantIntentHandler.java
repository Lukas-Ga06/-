package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.Memo;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.service.MemoService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.util.Recurrence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 旅行场景内的备忘 / 日程答复生成。
 *
 * <p>从 {@link AgentEngine} 抽出，只负责「意图 → 直接执行 + 用户可读文案」；
 * 状态机、路由、取消与 SSE 事件仍由引擎控制。
 *
 * <p>P0 产品收缩后只保留与旅行相关的两类命令（行程备忘、出行日程提醒）：
 * 日报与健康意图已退役，不再有落库入口。
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

    /** 备忘：添加（旅行待办，如「明天办签证」） */
    public AgentDtos.AgentResult addMemo(AgentContext ctx, IntentResult intent) {
        String content = MessageParser.parseMemoContent(ctx.getMessage());
        if (content == null || content.isBlank()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请告诉我备忘内容，比如「记一下明天办签证」。")
                    .build());
        }
        Memo memo = memoService.add(ctx.getUserId(), content, ctx.getTripId());
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

    /** 日程：添加（出行相关提醒，如「明天下午3点提醒我办签证」） */
    public AgentDtos.AgentResult addSchedule(AgentContext ctx, IntentResult intent) {
        MessageParser.ScheduleRequest request = MessageParser.parseScheduleRequest(ctx.getMessage());
        if (request == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请告诉我日程事项，比如「明天下午3点去办签证」。")
                    .build());
        }
        ScheduleItem item = scheduleService.add(ctx.getUserId(), request.title(),
                request.date(), request.time(), null, request.recurrence(), null, ctx.getTripId(), null);
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
                    .message("📅 日程是空的，说「明天下午3点去办签证」即可添加。")
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

    private AgentDtos.AgentResult complete(AgentContext ctx, AgentDtos.AgentResult result) {
        // P0-B：SSE 的 complete 事件在 handler 里发出，多意图提示必须在这里就挂上，
        // 否则包一层引擎的 REST 返回值有提示、SSE 用户却看不到（finish 处再挂靠前缀幂等）。
        MultiIntentNotices.attach(ctx.getAlsoRequested(), result);
        ctx.emit("complete", result);
        return result;
    }
}
