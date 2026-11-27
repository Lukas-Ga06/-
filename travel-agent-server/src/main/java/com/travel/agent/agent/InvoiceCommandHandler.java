package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ExpenseDraft;
import com.travel.agent.service.InvoiceMatchService;
import com.travel.agent.service.ocr.InvoiceFields;
import com.travel.agent.service.ocr.OcrInvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「报销这张发票」（{@link IntentResult#RECORD_INVOICE}）的方案生成。
 *
 * <p>链路：识别最新发票照片 → 规则匹配候选关联 → 生成一条 {@link PlanOperation#ADD_EXPENSE} 操作 →
 * {@link ExecutorAgent#preview} 出 Diff（识别结果就写在 Diff 里，识别错了用户在确认前就能看出来）→
 * 落 {@code pending_plan}（带内容指纹）→ 用户确认后由 {@link ExecutorAgent#commit} 单事务落账。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li><b>不返回模拟结果</b>：未配置 OCR / 识别失败 / 没识别出金额都不生成方案，只如实说明原因与替代做法；</li>
 *   <li><b>方案没有行程</b>：{@code plan.tripId} 为空，关联写在操作里——走行程路径会绑定行程指纹与操作历史，
 *       而记账不是行程变更；</li>
 *   <li><b>关联是推断</b>：同日期日程 / 同城且覆盖该日期的行程都是规则匹配，Diff 里逐条写明关联到谁，
 *       匹配不到就明确写「未关联」，不硬塞一个对象。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvoiceCommandHandler {

    /** 发票没有支付渠道信息，落账默认现金（记账页可改） */
    static final String DEFAULT_PAYMENT_METHOD = "现金";

    /** note 列宽 200：商户名超长时截断，避免落库失败 */
    private static final int NOTE_MAX_LENGTH = 200;

    private final OcrInvoiceService ocrInvoiceService;
    private final InvoiceMatchService invoiceMatchService;
    private final ExecutorAgent executorAgent;
    private final PlanRevisionService planRevisionService;
    private final PendingPlanStore pendingPlans;

    /** 生成「识别 + 记账 + 关联」的待确认方案；识别不可用时只答复，不产生 pending_plan */
    public AgentDtos.AgentResult recordInvoice(AgentContext ctx, IntentResult intent) {
        OcrInvoiceService.InvoiceLookup lookup = ocrInvoiceService.recognizeLatest(ctx.getUserId());
        if (!lookup.available()) {
            log.info("发票报销未生成方案: user={} reason={}", ctx.getUsername(), lookup.reason());
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message(failureText(lookup))
                    .warnings(new ArrayList<>())
                    .build());
        }

        InvoiceFields fields = lookup.fields();
        LocalDate spentAt = fields.invoiceDate() == null ? LocalDate.now() : fields.invoiceDate();
        String merchant = blankToNull(fields.merchant());
        String category = fields.category() == null ? com.travel.agent.entity.Expense.CAT_OTHER : fields.category();
        // 关联判定用「商户 + 识别原文」：票面上的城市/店名通常出现在这两处
        String haystack = (merchant == null ? "" : merchant) + " " + (fields.rawText() == null ? "" : fields.rawText());
        InvoiceMatchService.InvoiceCandidates candidates =
                invoiceMatchService.match(ctx.getUserId(), spentAt, haystack);

        ExpenseDraft draft = new ExpenseDraft(
                candidates.tripId(), candidates.scheduleItemId(), category, fields.amount(),
                merchant == null ? null : truncate(merchant, NOTE_MAX_LENGTH), spentAt, DEFAULT_PAYMENT_METHOD);
        String planId = UUID.randomUUID().toString();
        AgentPlan plan = AgentPlan.builder()
                .planId(planId)
                .intent(IntentResult.RECORD_INVOICE)
                // 关联写在操作里：不绑行程就不会走行程指纹与操作历史，也不会把账单算进行程预算
                .tripId(null)
                .runId(ctx.getRunId())
                .operations(List.of(PlanOperation.builder()
                        .type(PlanOperation.ADD_EXPENSE)
                        .expenseDraft(draft)
                        .detail(linkDetail(candidates))
                        .build()))
                .reasoning("报销发票：" + (merchant == null ? "未识别出商户" : merchant)
                        + " ¥" + amountText(fields.amount()) + "（" + spentAt + " · " + category + "）")
                .focus(AgentDtos.AgentFocus.builder().type("budget").tripId(candidates.tripId()).build())
                .build();
        // 记住被识别的照片：排查「同一张发票被重复报销」时能直接回查（不新增存储）
        plan.getExtras().put("invoicePhotoId", lookup.photoId());

        AgentDtos.AgentResult preview = executorAgent.preview(ctx, plan, null);
        preview.setMessage(successText(fields, merchant, category, spentAt, candidates));
        // 本方案没有行程/日程范围：revision 为 null，确认门只靠 contentHash 单指纹（R4 允许的退化）
        String revision = planRevisionService.revision(plan, null, ctx.getUserId());
        // put 会回填 confirmation.contentHash，所以必须先 put 再发 confirmation 事件
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
        log.info("发票报销生成待确认方案: user={} photoId={} amount={} tripId={} scheduleItemId={}",
                ctx.getUsername(), lookup.photoId(), fields.amount(), candidates.tripId(), candidates.scheduleItemId());
        return complete(ctx, preview);
    }

    /** 三种不可用各自的说法：都要给出「下一步能做什么」，且都不生成方案 */
    private static String failureText(OcrInvoiceService.InvoiceLookup lookup) {
        if (OcrInvoiceService.InvoiceLookup.REASON_NOT_FOUND.equals(lookup.reason())) {
            return "没有找到已上传的发票照片。请先在行程相册上传发票（上传类型 kind=INVOICE），"
                    + "再说「报销这张发票」。";
        }
        if (OcrInvoiceService.InvoiceLookup.REASON_NOT_CONFIGURED.equals(lookup.reason())) {
            return "OCR 发票识别未配置（需要设置 OCR_PROVIDER=baidu 与 OCR_BAIDU_API_KEY/OCR_BAIDU_SECRET_KEY），"
                    + "本次没有识别，也不会用模拟结果生成记账。可以手动说「记一笔 120 元」。";
        }
        return "发票识别失败（" + lookup.reason() + "）。没有生成记账方案；可以重拍或手动记账。";
    }

    /** 识别成功时的回复：把识别出的字段原样摊给用户核对，确认前就能发现识别错误 */
    private static String successText(InvoiceFields fields, String merchant, String category,
                                      LocalDate spentAt, InvoiceMatchService.InvoiceCandidates candidates) {
        return "识别到一张发票：" + (merchant == null ? "未识别出商户" : merchant)
                + " ¥" + amountText(fields.amount())
                + "（" + spentAt + " · " + category + "），确认后记一笔并" + linkClause(candidates)
                + "；识别有误可取消后手动记账。";
    }

    /** Diff 的 detail：关联到谁必须逐条写出来，匹配不到就明说未关联 */
    private static String linkDetail(InvoiceMatchService.InvoiceCandidates candidates) {
        List<String> links = new ArrayList<>(2);
        if (candidates.tripId() != null) {
            links.add("行程 " + candidates.tripLabel());
        }
        if (candidates.scheduleItemId() != null) {
            links.add("日程 " + candidates.scheduleLabel());
        }
        return links.isEmpty() ? "关联：未关联（没有匹配的行程或日程）" : "关联：" + String.join("、", links);
    }

    /** 回复文案里的关联从句（与 Diff 用的是同一批候选，措辞更口语） */
    private static String linkClause(InvoiceMatchService.InvoiceCandidates candidates) {
        if (candidates.tripId() != null && candidates.scheduleItemId() != null) {
            return "关联到「行程 " + candidates.tripLabel() + "」与「日程 " + candidates.scheduleLabel() + "」";
        }
        if (candidates.tripId() != null) {
            return "关联到「行程 " + candidates.tripLabel() + "」";
        }
        if (candidates.scheduleItemId() != null) {
            return "关联到「日程 " + candidates.scheduleLabel() + "」";
        }
        return "记为未关联行程的账单";
    }

    private static String amountText(BigDecimal amount) {
        return amount == null ? "?" : amount.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String truncate(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private AgentDtos.AgentResult complete(AgentContext ctx, AgentDtos.AgentResult result) {
        // 与 ScheduleCommandHandler#complete 同一口径：多意图提示在 handler 里挂上，
        // 引擎 finish 处再按前缀幂等兜一层
        MultiIntentNotices.attach(ctx.getAlsoRequested(), result);
        ctx.emit("complete", result);
        return result;
    }
}