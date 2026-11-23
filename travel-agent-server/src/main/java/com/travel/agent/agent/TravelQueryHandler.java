package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.BudgetDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.rag.RagService;
import com.travel.agent.service.BudgetService;
import com.travel.agent.service.ExpenseService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TravelCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 行程域只读查询 + 记账类意图的答复生成：知识问答 / 预算 / 记账。
 *
 * <p>从 {@link AgentEngine} 抽出。记账会立即落库，因此票据只读会话在引擎路由前已被拦截；
 * 本类不含取消检查（取消边界由引擎在调用前后统一判断）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TravelQueryHandler {

    private final RagService ragService;
    private final BudgetService budgetService;
    private final ExpenseService expenseService;
    private final ItineraryService itineraryService;
    private final TravelCatalogService catalogService;

    /** RAG 知识问答：个人知识库只对本人可见，公共知识（userId 为空）所有人可见 */
    public AgentDtos.AgentResult answerKnowledge(AgentContext ctx, IntentResult intent, Trip trip) {
        String city = trip != null ? trip.getDestination() : null;
        if (city == null) {
            String message = ctx.getMessage() == null ? "" : ctx.getMessage();
            city = catalogService.cities().stream()
                    .map(c -> c.name())
                    .filter(message::contains)
                    .findFirst().orElse(null);
        }
        RagService.RagAnswer answer = ragService.answer(
                ctx.getMessage() == null ? "" : ctx.getMessage(), city, ctx.getUserId());
        // P1：SSE 同时给出 sources（文案）与 citations（documentId/version/chunkId/score/source），
        // 前端可把「知识依据」列在回答下方，而不是只展示一句来源标题。
        ctx.emit("rag_result", java.util.Map.of(
                "sources", answer.sources(),
                "citations", answer.citations()));
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(answer.message())
                .warnings(new ArrayList<>())
                .build());
    }

    /** 预算：计划汇总 + 实际支出对照 */
    public AgentDtos.AgentResult answerBudget(AgentContext ctx, IntentResult intent, Trip trip) {
        if (trip == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请先选择一个行程，我就能帮你算预算啦。")
                    .build());
        }
        List<ItineraryItem> items = itineraryService.listEntities(trip.getId());
        AgentDtos.BudgetSummary summary = budgetService.summarize(items);
        BudgetDtos.BudgetExecution execution = budgetService.execution(trip, items,
                expenseService.listEntitiesByTrip(trip.getId()));
        return complete(ctx, AgentDtos.AgentResult.builder()
                .tripId(trip.getId())
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(MessageTemplates.budgetExecution(execution))
                .focus(AgentDtos.AgentFocus.builder().type("budget").tripId(trip.getId()).build())
                .budget(summary)
                .warnings(new ArrayList<>())
                .build());
    }

    /** 记账：解析金额与分类后立即落库，并回显累计支出与预算超限提示 */
    public AgentDtos.AgentResult recordExpense(AgentContext ctx, IntentResult intent, Trip trip) {
        if (trip == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("记账前请先选择一个行程；进入行程后说「记一笔午餐 45 元」即可。")
                    .build());
        }
        MessageParser.ExpenseRequest request = MessageParser.parseExpenseRequest(ctx.getMessage());
        if (request == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请告诉我金额，比如「记一笔午餐 45 元」或「打车花了 30 元」。")
                    .build());
        }
        LocalDate spentAt = LocalDate.now().plusDays(request.dayOffset());
        String note = truncateNote(ctx.getMessage());
        Expense saved = expenseService.add(trip.getId(), ctx.getUserId(), request.category(),
                request.amount(), note, spentAt, request.paymentMethod());
        BigDecimal total = expenseService.totalOf(trip.getId());
        StringBuilder sb = new StringBuilder("✅ 已记账：").append(saved.getCategory())
                .append(" ¥").append(saved.getAmount().stripTrailingZeros().toPlainString())
                .append("（").append(spentAt).append(" · ").append(saved.getPaymentMethod()).append("）。本行程累计支出 ¥")
                .append(total.stripTrailingZeros().toPlainString());
        String over = "";
        if (trip.getBudget() != null) {
            sb.append(" / 预算 ¥").append(trip.getBudget().stripTrailingZeros().toPlainString());
            if (total.compareTo(trip.getBudget()) > 0) {
                over = "（已超出预算 ⚠）";
            }
        }
        return complete(ctx, AgentDtos.AgentResult.builder()
                .tripId(trip.getId())
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(sb + over)
                .focus(AgentDtos.AgentFocus.builder().type("budget").tripId(trip.getId()).build())
                .warnings(new ArrayList<>())
                .build());
    }

    /** 备注取用户原话（截断 200 字），null 消息保持 null */
    private String truncateNote(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private AgentDtos.AgentResult complete(AgentContext ctx, AgentDtos.AgentResult result) {
        // P0-B：SSE 的 complete 事件在 handler 里发出，多意图提示必须在这里就挂上，
        // 否则包一层引擎的 REST 返回值有提示、SSE 用户却看不到（finish 处再挂靠前缀幂等）。
        MultiIntentNotices.attach(ctx.getAlsoRequested(), result);
        ctx.emit("complete", result);
        return result;
    }
}
