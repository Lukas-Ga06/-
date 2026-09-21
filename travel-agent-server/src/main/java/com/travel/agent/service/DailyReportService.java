package com.travel.agent.service;

import com.travel.agent.entity.DailyReport;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.Memo;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.DailyReportRepository;
import com.travel.agent.repository.ExpenseRepository;
import com.travel.agent.repository.MemoRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 日报生成：汇总当日记账/备忘/日程真实数据 → LLM 写作（无 LLM 时输出数据汇总） */
@Service
@RequiredArgsConstructor
public class DailyReportService {

    private final DailyReportRepository reportRepository;
    private final ExpenseRepository expenseRepository;
    private final MemoRepository memoRepository;
    private final ScheduleItemRepository scheduleItemRepository;
    private final LlmAssistantService llmAssistantService;

    /** 用户全部日报（按日期倒序） */
    public List<DailyReport> list(Long userId) {
        return reportRepository.findByUserIdOrderByDateDescIdDesc(userId);
    }

    @Transactional
    public DailyReport generate(Long userId, LocalDate date) {
        DateTimeFormatter md = DateTimeFormatter.ofPattern("M月d日");
        StringBuilder facts = new StringBuilder();

        List<Expense> todayExpenses = expenseRepository.findByUserIdOrderBySpentAtDescIdDesc(userId).stream()
                .filter(e -> date.equals(e.getSpentAt())).toList();
        BigDecimal expenseTotal = todayExpenses.stream()
                .map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        facts.append(todayExpenses.isEmpty()
                ? "- 今日无记账记录\n"
                : "- 今日记账 " + todayExpenses.size() + " 笔，合计 ¥" + expenseTotal + "（"
                        + todayExpenses.stream().map(e -> e.getCategory() + " ¥" + e.getAmount())
                        .reduce((a, b) -> a + "、" + b).orElse("") + "）\n");

        List<Memo> memos = memoRepository.findByUserIdOrderByDoneAscCreatedAtDesc(userId);
        List<Memo> todayMemos = memos.stream()
                .filter(m -> m.getCreatedAt() != null && date.equals(m.getCreatedAt().toLocalDate())).toList();
        if (!todayMemos.isEmpty()) {
            facts.append("- 今日新增备忘：").append(todayMemos.stream()
                    .map(Memo::getContent).reduce((a, b) -> a + "；" + b).orElse("")).append('\n');
        }
        long doneMemos = memos.stream()
                .filter(Memo::isDone)
                .filter(m -> m.getCreatedAt() != null && date.equals(m.getCreatedAt().toLocalDate())).count();
        if (doneMemos > 0) {
            facts.append("- 今日完成备忘 ").append(doneMemos).append(" 项\n");
        }

        List<ScheduleItem> todaySchedule = scheduleItemRepository
                .findByUserIdOrderByDateAscTimeAscIdAsc(userId).stream()
                .filter(s -> date.equals(s.getDate())).toList();
        if (!todaySchedule.isEmpty()) {
            facts.append("- 今日日程：").append(todaySchedule.stream()
                    .map(ScheduleItem::getTitle).reduce((a, b) -> a + "；" + b).orElse("")).append('\n');
        }
        if (facts.isEmpty()) {
            facts.append("- 今日暂无记录数据\n");
        }

        String content = llmAssistantService.generateDailyReport(
                "日期：" + date.format(md) + "\n" + facts);

        DailyReport saved = reportRepository.findByUserIdAndDate(userId, date)
                .map(r -> {
                    r.setContent(content);
                    return reportRepository.save(r);
                })
                .orElseGet(() -> reportRepository.save(DailyReport.builder()
                        .userId(userId).date(date).content(content).build()));
        return saved;
    }
}
