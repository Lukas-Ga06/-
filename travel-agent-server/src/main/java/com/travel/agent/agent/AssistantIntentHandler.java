package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.Memo;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.service.FreeTimeService;
import com.travel.agent.service.MemoService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.util.Recurrence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 旅行场景内的备忘 / 日程 / 空闲时段答复生成。
 *
 * <p>从 {@link AgentEngine} 抽出，只负责「意图 → 直接执行 + 用户可读文案」；
 * 状态机、路由、取消与 SSE 事件仍由引擎控制。
 *
 * <p>P0 产品收缩后只保留与旅行相关的命令（行程备忘、出行日程提醒、空闲时段查询）：
 * 日报与健康意图已退役，不再有落库入口。
 *
 * <p>备忘/日程会立即落库（记账在 {@link TravelQueryHandler}），因此票据只读会话在
 * 引擎路由前就被拦掉；空闲查询只读，不受该限制。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantIntentHandler {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日");

    /** 回答里最多列出的占用条目 / 无时间日程条数 */
    private static final int MAX_LISTED_ITEMS = 5;

    private final MemoService memoService;
    private final ScheduleService scheduleService;
    private final FreeTimeService freeTimeService;

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

    /** 空闲时段：只读查询，给出空闲段、建议槽位，并在末尾固定下一步引导 */
    public AgentDtos.AgentResult queryFreeTime(AgentContext ctx, IntentResult intent) {
        LocalDate today = LocalDate.now();
        MessageParser.DateRange range = MessageParser.parseFreeTimeRange(ctx.getMessage(), today);
        int minutes = MessageParser.parseMeetingMinutes(ctx.getMessage());
        FreeTimeService.FreeTimeReport report = freeTimeService.query(
                ctx.getUserId(), range.from(), range.to(), minutes);
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(renderFreeTime(report, today))
                // 前端目前只处理 itinerary / budget，未知类型会被忽略；这里保持安全的最小信息
                .focus(AgentDtos.AgentFocus.builder().type("schedule").build())
                .warnings(new ArrayList<>())
                .build());
    }

    private String renderFreeTime(FreeTimeService.FreeTimeReport report, LocalDate today) {
        List<FreeTimeService.DayFreeTime> days = report.days();
        String duration = durationText(report.meetingMinutes());
        String window = hm(report.windowStart()) + "–" + hm(report.windowEnd());
        StringBuilder sb = new StringBuilder("🗓 ");
        if (days.size() == 1) {
            FreeTimeService.DayFreeTime day = days.get(0);
            if (day.suggestions().isEmpty()) {
                sb.append(relativeDay(day.date(), today)).append(' ').append(window)
                        .append(" 没有 ").append(duration).append("以上的连续空闲。");
            } else {
                sb.append(relativeDay(day.date(), today)).append('（').append(monthDay(day.date())).append('）')
                        .append(window).append(" 的空闲：").append(slotText(day.free()));
            }
        } else {
            sb.append(monthDay(days.get(0).date())).append('–')
                    .append(monthDay(days.get(days.size() - 1).date()))
                    .append("，每天 ").append(window).append(" 的空闲（会议时长 ").append(duration).append("）：");
            for (FreeTimeService.DayFreeTime day : days) {
                sb.append("\n• ").append(monthDay(day.date())).append("：");
                if (day.suggestions().isEmpty()) {
                    sb.append("没有 ").append(duration).append("以上的连续空闲");
                } else {
                    sb.append("空闲 ").append(slotText(day.free()))
                            .append("；建议 ").append(suggestText(day.suggestions())).append(" 开会");
                }
            }
        }
        if (days.size() == 1 && !days.get(0).suggestions().isEmpty()) {
            sb.append("\n建议 ").append(suggestText(days.get(0).suggestions()))
                    .append('（').append(duration).append("）开会。");
        }
        String busy = busyText(days);
        if (!busy.isEmpty()) {
            sb.append('\n').append(busy);
        }
        if (!report.untimedSchedules().isEmpty()) {
            sb.append("\n另有 ").append(report.untimedSchedules().size())
                    .append(" 项没有具体时间的日程未计入占用：")
                    .append(cappedJoin(report.untimedSchedules())).append('。');
        }
        sb.append("\n回复「安排").append(relativeDay(days.get(0).date(), today)).append("10点开会」即可创建日程。");
        return sb.toString();
    }

    /** 占用条目：HH:mm 标题；单日不带日期，多日带上「M月d日」前缀，最多 5 条 */
    private String busyText(List<FreeTimeService.DayFreeTime> days) {
        boolean multiDay = days.size() > 1;
        List<String> listed = new ArrayList<>();
        int total = 0;
        for (FreeTimeService.DayFreeTime day : days) {
            for (FreeTimeService.BusyBlock block : day.busy()) {
                total++;
                if (listed.size() < MAX_LISTED_ITEMS) {
                    listed.add((multiDay ? monthDay(day.date()) + " " : "")
                            + hm(block.start()) + " " + block.title());
                }
            }
        }
        if (listed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("已占：").append(String.join("、", listed));
        if (total > listed.size()) {
            sb.append(" 等 ").append(total).append(" 项");
        }
        return sb.append('。').toString();
    }

    private String slotText(List<FreeTimeService.FreeSlot> slots) {
        List<String> parts = new ArrayList<>();
        for (FreeTimeService.FreeSlot slot : slots) {
            parts.add(hm(slot.start()) + "–" + hm(slot.end()));
        }
        return String.join("、", parts);
    }

    private String suggestText(List<FreeTimeService.Suggestion> slots) {
        List<String> parts = new ArrayList<>();
        for (FreeTimeService.Suggestion slot : slots) {
            parts.add(hm(slot.start()) + "–" + hm(slot.end()));
        }
        return String.join("、", parts);
    }

    /** 最多列 5 条，超出时用「等」收尾（数量已在句首说过，不重复） */
    private String cappedJoin(List<String> items) {
        List<String> listed = items.size() > MAX_LISTED_ITEMS
                ? items.subList(0, MAX_LISTED_ITEMS) : items;
        String joined = String.join("、", listed);
        return items.size() > MAX_LISTED_ITEMS ? joined + " 等" : joined;
    }

    private String durationText(int minutes) {
        return minutes >= 60 && minutes % 60 == 0 ? (minutes / 60) + " 小时" : minutes + " 分钟";
    }

    private String relativeDay(LocalDate date, LocalDate today) {
        long offset = ChronoUnit.DAYS.between(today, date);
        return switch ((int) offset) {
            case 0 -> "今天";
            case 1 -> "明天";
            case 2 -> "后天";
            case 3 -> "大后天";
            default -> monthDay(date);
        };
    }

    private String monthDay(LocalDate date) {
        return date.format(MONTH_DAY);
    }

    private String hm(LocalTime time) {
        return time.format(HH_MM);
    }

    private AgentDtos.AgentResult complete(AgentContext ctx, AgentDtos.AgentResult result) {
        // P0-B：SSE 的 complete 事件在 handler 里发出，多意图提示必须在这里就挂上，
        // 否则包一层引擎的 REST 返回值有提示、SSE 用户却看不到（finish 处再挂靠前缀幂等）。
        MultiIntentNotices.attach(ctx.getAlsoRequested(), result);
        ctx.emit("complete", result);
        return result;
    }
}
