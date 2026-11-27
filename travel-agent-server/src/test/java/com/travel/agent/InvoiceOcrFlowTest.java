package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.TripPhoto;
import com.travel.agent.repository.ExpenseRepository;
import com.travel.agent.security.UserContext;
import com.travel.agent.service.PhotoService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.service.TripService;
import com.travel.agent.service.ocr.InvoiceFields;
import com.travel.agent.service.ocr.OcrClient;
import com.travel.agent.service.ocr.OcrException;
import com.travel.agent.support.IntegrationTestSupport;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发票报销（RECORD_INVOICE）的端到端闭环：识别 → 候选关联 → 确认门 → 单事务落账。
 *
 * <p>用脚本化 {@link OcrClient} 替身，<b>不触网</b>（R6）：真实百度识别的质量无法在没有 Key 的情况下验证，
 * 这里只验证「未配置/失败不生成方案、成功经确认后落账、关联字段正确」这些代码能证明的行为。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>未配置 OCR / 识别失败 → 明确报不可用，<b>不生成方案、不写账单</b>（绝不返回模拟结果）；</li>
 *   <li>成功 → NEED_CONFIRMATION，Diff 里能看到识别结果与关联对象；不确认就不落库；</li>
 *   <li>确认后落一条账单，金额/日期/类目/关联行程与日程与识别结果一致；无候选时 tripId/scheduleItemId 为空。</li>
 * </ul>
 */
class InvoiceOcrFlowTest extends IntegrationTestSupport {

    private static final String MESSAGE = "报销这张发票";
    private static final String PNG_NAME = "发票.png";

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private PhotoService photoService;

    @Autowired
    private TripService tripService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private ToolRegistry toolRegistry;

    /** 每个用例都从「未配置 + 无字段 + 不失败」开始，避免脚本替身的静态状态串场 */
    @BeforeEach
    void resetOcrStub() {
        ScriptedOcrClient.available = false;
        ScriptedOcrClient.fields = null;
        ScriptedOcrClient.failure = null;
    }

    /** 脚本化 OcrClient 替身：行为完全由测试静态设定，永不出网 */
    @TestConfiguration
    static class ScriptedOcrConfig {
        @Bean
        @Primary
        OcrClient scriptedOcrClient() {
            return new ScriptedOcrClient();
        }
    }

    static class ScriptedOcrClient implements OcrClient {

        static volatile boolean available;
        static volatile InvoiceFields fields;
        static volatile RuntimeException failure;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public InvoiceFields recognize(byte[] imageBytes) {
            if (failure != null) {
                throw failure;
            }
            return fields;
        }
    }

    private static MockMultipartFile pngFile() {
        // 最小 PNG 头（服务端按文件头判类型，测试桩也必须是真的 PNG 头）
        byte[] bytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        return new MockMultipartFile("file", PNG_NAME, "image/png", bytes);
    }

    private Trip newTrip(Account account, String destination, LocalDate start, LocalDate end) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName(destination + "差旅");
        request.setDestination(destination);
        request.setStartDate(start.toString());
        request.setEndDate(end.toString());
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(account.id(), request);
    }

    /** 上传发票：复用照片通道，kind=INVOICE（V32） */
    private TripPhoto uploadInvoice(Account account, Trip trip) {
        return photoService.upload(trip.getId(), account.id(), pngFile(), TripPhoto.KIND_INVOICE);
    }

    private AgentDtos.AgentResult run(Account account, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage(message);
        return agentEngine.handle(ctx);
    }

    private JsonNode confirm(Account account, AgentDtos.AgentResult plan) throws Exception {
        String request = objectMapper.writeValueAsString(Map.of(
                "planId", plan.getConfirmation().getPlanId(),
                "contentHash", plan.getConfirmation().getContentHash()));
        MvcResult result = mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    private List<Expense> expensesOf(Account account) {
        return expenseRepository.findByUserIdOrderBySpentAtDescIdDesc(account.id());
    }

    @Test
    @DisplayName("未配置 OCR：明确报「未配置」，不生成方案、不写账单")
    void notConfiguredReportsUnavailable() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account, "上海", LocalDate.now(), LocalDate.now());
        uploadInvoice(account, trip);

        AgentDtos.AgentResult result = run(account, MESSAGE);

        assertThat(result.getIntent()).isEqualTo(IntentResult.RECORD_INVOICE);
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getMessage()).contains("未配置");
        assertThat(result.getConfirmation()).isNull();
        assertThat(result.getDiff()).isNull();
        assertThat(expensesOf(account)).isEmpty();
    }

    @Test
    @DisplayName("识别失败：报「识别失败」+ 原因，不生成方案、不写账单")
    void recognitionFailureReportsReason() throws Exception {
        ScriptedOcrClient.available = true;
        ScriptedOcrClient.failure = new OcrException("发票识别请求失败（timeout）", "timeout");
        Account account = registerNew();
        Trip trip = newTrip(account, "上海", LocalDate.now(), LocalDate.now());
        uploadInvoice(account, trip);

        AgentDtos.AgentResult result = run(account, MESSAGE);

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getMessage()).contains("识别失败").contains("timeout");
        assertThat(result.getConfirmation()).isNull();
        assertThat(expensesOf(account)).isEmpty();
    }

    @Test
    @DisplayName("识别到内容但 amount 为 null 或 ≤0：不生成方案，提示「没有识别出金额」")
    void missingAmountDoesNotProducePlan() throws Exception {
        LocalDate today = LocalDate.now();
        ScriptedOcrClient.available = true;
        Account account = registerNew();
        Trip trip = newTrip(account, "上海", today, today);
        uploadInvoice(account, trip);

        ScriptedOcrClient.fields = new InvoiceFields(null, "上海某某餐饮有限公司",
                today, null, "上海某某餐饮有限公司 餐饮服务 合计金额无法识别");
        AgentDtos.AgentResult nullAmount = run(account, MESSAGE);

        assertThat(nullAmount.getIntent()).isEqualTo(IntentResult.RECORD_INVOICE);
        assertThat(nullAmount.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(nullAmount.getMessage()).contains("没有识别出金额");
        assertThat(nullAmount.getConfirmation()).isNull();
        assertThat(nullAmount.getDiff()).isNull();

        ScriptedOcrClient.fields = new InvoiceFields(BigDecimal.ZERO, "上海某某餐饮有限公司",
                today, null, "上海某某餐饮有限公司 餐饮服务 AmountInFiguers=0.00");
        AgentDtos.AgentResult zeroAmount = run(account, MESSAGE);

        assertThat(zeroAmount.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(zeroAmount.getMessage()).contains("没有识别出金额");
        assertThat(zeroAmount.getConfirmation()).isNull();
        assertThat(zeroAmount.getDiff()).isNull();
        assertThat(expensesOf(account)).isEmpty();
    }

    @Test
    @DisplayName("识别成功且有候选：Diff 里能看到金额与关联，确认后落账并带上行程/日程")
    void successWithCandidatesGoesThroughConfirmGate() throws Exception {
        LocalDate today = LocalDate.now();
        ScriptedOcrClient.available = true;
        ScriptedOcrClient.fields = new InvoiceFields(new BigDecimal("128.50"), "上海某某餐饮有限公司",
                today, null, "上海某某餐饮有限公司 *餐饮服务*餐费 AmountInFiguers=128.50");
        Account account = registerNew();
        Trip trip = newTrip(account, "上海", today.minusDays(1), today.plusDays(1));
        ScheduleItem schedule = scheduleService.add(account.id(), "客户汇报", today, LocalTime.of(9, 0), null);
        uploadInvoice(account, trip);

        AgentDtos.AgentResult plan = run(account, MESSAGE);

        assertThat(plan.getIntent()).isEqualTo(IntentResult.RECORD_INVOICE);
        assertThat(plan.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(plan.getDiff().getChanges()).hasSize(1);
        AgentDtos.DiffChange change = plan.getDiff().getChanges().get(0);
        assertThat(change.getType()).isEqualTo("ADD");
        assertThat(change.getItemType()).isEqualTo("expense");
        assertThat(change.getTitle()).contains("记账：餐饮").contains("128.5");
        assertThat(change.getDetail()).contains("关联：").contains("行程").contains("日程")
                .contains("识别摘要").contains("AmountInFiguers=128.50");
        assertThat(change.getStartTime()).isEqualTo(today.toString());
        // 账单不进行程预算：cost 不设，预算保持 0 → 0
        assertThat(change.getCost()).isNull();
        assertThat(plan.getDiff().getBudgetBefore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(plan.getConfirmation().getContentHash()).isNotBlank();
        // 不确认就不落库
        assertThat(expensesOf(account)).isEmpty();

        JsonNode confirmed = confirm(account, plan);

        assertThat(confirmed.path("status").asText()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(confirmed.path("message").asText()).contains("已记一笔").contains("关联：行程");
        List<Expense> expenses = expensesOf(account);
        assertThat(expenses).hasSize(1);
        Expense expense = expenses.get(0);
        assertThat(expense.getTripId()).isEqualTo(trip.getId());
        assertThat(expense.getScheduleItemId()).isEqualTo(schedule.getId());
        assertThat(expense.getAmount()).isEqualByComparingTo(new BigDecimal("128.50"));
        assertThat(expense.getSpentAt()).isEqualTo(today);
        assertThat(expense.getCategory()).isEqualTo("餐饮");
        assertThat(expense.getNote()).isEqualTo("上海某某餐饮有限公司");
        assertThat(expense.getPaymentMethod()).isEqualTo("现金");
    }

    @Test
    @DisplayName("识别摘要剔除密码区/校验码：Diff 与确认摘要都不出现敏感片段")
    void sensitiveTicketFieldsNeverReachDiff() throws Exception {
        LocalDate today = LocalDate.now();
        ScriptedOcrClient.available = true;
        // 即使替身（或换回来的客户端实现）把密码区塞进 rawText，第二道过滤也必须拦住
        ScriptedOcrClient.fields = new InvoiceFields(new BigDecimal("128.50"), "上海某某餐饮有限公司",
                today, null,
                "上海某某餐饮有限公司 *餐饮服务*餐费 Password=SECRET123 CheckCode=SECRET456 校验码：SECRET789");
        Account account = registerNew();
        Trip trip = newTrip(account, "上海", today, today);
        uploadInvoice(account, trip);

        AgentDtos.AgentResult plan = run(account, MESSAGE);

        assertThat(plan.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        String detail = plan.getDiff().getChanges().get(0).getDetail();
        assertThat(detail)
                .contains("识别摘要").contains("餐饮服务").contains("上海某某餐饮有限公司")
                .doesNotContain("SECRET123").doesNotContain("SECRET456").doesNotContain("SECRET789")
                .doesNotContain("Password").doesNotContain("CheckCode");
        // 确认摘要复用同一段 detail，同样不能带敏感片段
        String confirmation = plan.getConfirmation().getSummary();
        assertThat(confirmation)
                .contains("识别摘要")
                .doesNotContain("SECRET123").doesNotContain("SECRET456").doesNotContain("SECRET789")
                .doesNotContain("Password").doesNotContain("CheckCode");
        // 敏感信息只在识别侧过滤，金额/商户/关联照旧
        assertThat(detail).contains("关联：").contains("行程");
        assertThat(plan.getConfirmation().getContentHash()).isNotBlank();
        assertThat(expensesOf(account)).isEmpty();
    }

    @Test
    @DisplayName("识别成功但没有候选：确认为未关联行程的账单（tripId / scheduleItemId 为空）")
    void successWithoutCandidatesStoresUnlinkedExpense() throws Exception {
        LocalDate today = LocalDate.now();
        ScriptedOcrClient.available = true;
        ScriptedOcrClient.fields = new InvoiceFields(new BigDecimal("56"), "上海某某便利店",
                today, null, "上海某某便利店 商品一批");
        Account account = registerNew();
        // 行程目的地是北京：票面出现的是上海，不同城 → 不自动关联
        Trip beijing = newTrip(account, "北京", today.minusDays(2), today.plusDays(2));
        uploadInvoice(account, beijing);

        AgentDtos.AgentResult plan = run(account, MESSAGE);

        assertThat(plan.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(plan.getDiff().getChanges()).hasSize(1);
        assertThat(plan.getDiff().getChanges().get(0).getDetail()).contains("未关联");

        JsonNode confirmed = confirm(account, plan);

        assertThat(confirmed.path("status").asText()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        List<Expense> expenses = expensesOf(account);
        assertThat(expenses).hasSize(1);
        assertThat(expenses.get(0).getTripId()).isNull();
        assertThat(expenses.get(0).getScheduleItemId()).isNull();
        assertThat(expenses.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("56"));
    }

    @Test
    @DisplayName("ocr_invoice 工具已注册且只读；未配置时返回 available=false 与原因，不编造字段")
    void ocrInvoiceToolIsReadOnlyAndHonest() throws Exception {
        AgentTool tool = toolRegistry.get("ocr_invoice");
        assertThat(tool).as("ocr_invoice 必须注册进 ToolRegistry").isNotNull();
        assertThat(tool.readOnly()).as("R1：工具层只读").isTrue();
        assertThat(tool.getParameters()).as("零参数：识别对象由当前用户 + 最新发票照片唯一确定").isEmpty();

        Account account = registerNew();
        UserContext.set(account.username());
        try {
            Object result = tool.execute(Map.of());
            assertThat(result).isInstanceOf(Map.class);
            Map<?, ?> payload = (Map<?, ?>) result;
            assertThat(payload.get("available")).isEqualTo(false);
            assertThat(payload.get("reason")).isInstanceOf(String.class);
            assertThat((String) payload.get("reason")).isNotBlank();
            assertThat(payload.get("amount")).isNull();
            assertThat(payload.get("merchant")).isNull();
        } finally {
            UserContext.clear();
        }
    }
}