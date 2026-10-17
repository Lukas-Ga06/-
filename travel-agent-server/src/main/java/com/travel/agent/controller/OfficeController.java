package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.ExpenseDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.Invoice;
import com.travel.agent.entity.MailItem;
import com.travel.agent.entity.MeetingMinutes;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.User;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.InvoiceRepository;
import com.travel.agent.repository.MailItemRepository;
import com.travel.agent.repository.MeetingMinutesRepository;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ExpenseService;
import com.travel.agent.service.LlmAssistantService;
import com.travel.agent.service.TripService;
import com.travel.agent.util.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 办公自动化：邮件分类 / 会议纪要 / 发票整理（日报见 ReportController） */
@RestController
@RequestMapping("/api/office")
@RequiredArgsConstructor
public class OfficeController {

    private final AuthService authService;
    private final LlmAssistantService llmAssistantService;
    private final TripService tripService;
    private final ExpenseService expenseService;
    private final MailItemRepository mailItemRepository;
    private final MeetingMinutesRepository minutesRepository;
    private final InvoiceRepository invoiceRepository;

    public record MailClassifyRequest(
            @Size(max = 200, message = "长度不能超过 200 个字符") String sender,
            @Size(max = 200, message = "长度不能超过 200 个字符") String subject,
            @NotBlank(message = "不能为空")
            @Size(max = 20000, message = "长度不能超过 20000 个字符") String text) {}

    @PostMapping("/mail/classify")
    public ApiResponse<MailItem> classifyMail(@Valid @RequestBody MailClassifyRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        String text = (request.text() == null ? "" : request.text()) + "\n"
                + (request.subject() == null ? "" : request.subject());
        LlmAssistantService.MailResult result = llmAssistantService.classifyMail(text);
        MailItem saved = mailItemRepository.save(MailItem.builder()
                .userId(user.getId())
                .sender(request.sender())
                .subject(request.subject())
                .body(request.text())
                .category(result.category())
                .summary(result.summary())
                .suggestedReply(result.suggestedReply())
                .build());
        return ApiResponse.ok(saved);
    }

    @GetMapping("/mail")
    public ApiResponse<List<MailItem>> mailList(
            @RequestParam(defaultValue = "" + Paging.DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(Paging.slice(
                mailItemRepository.findByUserIdOrderByCreatedAtDesc(user.getId()), offset, limit));
    }

    public record MinutesRequest(
            @Size(max = 200, message = "长度不能超过 200 个字符") String title,
            @NotBlank(message = "不能为空")
            @Size(max = 50000, message = "长度不能超过 50000 个字符") String rawText) {}

    @PostMapping("/minutes/generate")
    public ApiResponse<MeetingMinutes> generateMinutes(@Valid @RequestBody MinutesRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        String content = llmAssistantService.generateMinutes(request.title(), request.rawText());
        MeetingMinutes saved = minutesRepository.save(MeetingMinutes.builder()
                .userId(user.getId())
                .title(request.title() == null || request.title().isBlank() ? "未命名会议" : request.title())
                .rawText(request.rawText())
                .content(content)
                .build());
        return ApiResponse.ok(saved);
    }

    @GetMapping("/minutes")
    public ApiResponse<List<MeetingMinutes>> minutesList(
            @RequestParam(defaultValue = "" + Paging.DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(Paging.slice(
                minutesRepository.findByUserIdOrderByCreatedAtDesc(user.getId()), offset, limit));
    }

    public record InvoiceExtractRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 20000, message = "长度不能超过 20000 个字符") String text) {}

    @PostMapping("/invoice/extract")
    public ApiResponse<Invoice> extractInvoice(@Valid @RequestBody InvoiceExtractRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        LlmAssistantService.InvoiceResult result = llmAssistantService.extractInvoice(request.text());
        if (result == null) {
            throw new com.travel.agent.exception.BusinessException(400, "未能从文本中提取出发票信息，请检查内容（需包含金额）");
        }
        Invoice saved = invoiceRepository.save(Invoice.builder()
                .userId(user.getId())
                .title(result.title())
                .vendor(result.vendor())
                .category(result.category())
                .amount(result.amount())
                .invoiceDate(result.date())
                .rawText(request.text())
                .build());
        return ApiResponse.ok(saved);
    }

    @GetMapping("/invoice")
    public ApiResponse<List<Invoice>> invoiceList(
            @RequestParam(defaultValue = "" + Paging.DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(Paging.slice(
                invoiceRepository.findByUserIdOrderByInvoiceDateDescIdDesc(user.getId()), offset, limit));
    }

    public record InvoiceExpenseRequest(
            @NotNull(message = "不能为空") Long tripId,
            @Size(max = 30, message = "长度不能超过 30 个字符") String paymentMethod) {}

    /**
     * 发票一键记账：把发票金额写成所选行程的一笔支出，并把发票标记为已记账（防止重复入账）。
     */
    @PostMapping("/invoice/{id}/expense")
    public ApiResponse<ExpenseDtos.ExpenseDTO> invoiceToExpense(@PathVariable Long id,
                                                                @Valid @RequestBody InvoiceExpenseRequest request,
                                                                Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        Invoice invoice = invoiceRepository.findById(id)
                .filter(v -> v.getUserId().equals(user.getId()))
                .orElseThrow(() -> new BusinessException(404, "发票不存在"));
        if (invoice.getExpenseId() != null) {
            throw new BusinessException(409, "该发票已经记过账了");
        }
        if (invoice.getAmount() == null || invoice.getAmount().signum() <= 0) {
            throw new BusinessException(400, "发票金额无效，无法记账");
        }
        Trip trip = tripService.getOwned(request.tripId(), user.getId());

        String note = ("发票：" + (invoice.getTitle() == null ? "" : invoice.getTitle())
                + (invoice.getVendor() == null ? "" : " · " + invoice.getVendor())).trim();
        if (note.length() > 190) {
            note = note.substring(0, 190);
        }
        Expense expense = expenseService.add(trip.getId(), user.getId(),
                mapInvoiceCategory(invoice.getCategory()),
                invoice.getAmount(),
                note,
                invoice.getInvoiceDate() == null ? LocalDate.now() : invoice.getInvoiceDate(),
                request.paymentMethod());

        invoice.setExpenseId(expense.getId());
        invoice.setTripId(trip.getId());
        invoiceRepository.save(invoice);
        return ApiResponse.ok(ExpenseDtos.ExpenseDTO.from(expense));
    }

    /** 发票分类 → 记账分类（办公类归入「其他」） */
    private static String mapInvoiceCategory(String invoiceCategory) {
        Set<String> direct = Set.of("餐饮", "交通", "住宿", "门票", "购物");
        return invoiceCategory != null && direct.contains(invoiceCategory) ? invoiceCategory : "其他";
    }
}
