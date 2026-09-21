package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.Invoice;
import com.travel.agent.entity.MailItem;
import com.travel.agent.entity.MeetingMinutes;
import com.travel.agent.entity.User;
import com.travel.agent.repository.InvoiceRepository;
import com.travel.agent.repository.MailItemRepository;
import com.travel.agent.repository.MeetingMinutesRepository;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.LlmAssistantService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 办公自动化：邮件分类 / 会议纪要 / 发票整理（日报见 ReportController） */
@RestController
@RequestMapping("/api/office")
@RequiredArgsConstructor
public class OfficeController {

    private final AuthService authService;
    private final LlmAssistantService llmAssistantService;
    private final MailItemRepository mailItemRepository;
    private final MeetingMinutesRepository minutesRepository;
    private final InvoiceRepository invoiceRepository;

    public record MailClassifyRequest(String sender, String subject, String text) {}

    @PostMapping("/mail/classify")
    public ApiResponse<MailItem> classifyMail(@RequestBody MailClassifyRequest request, Authentication authentication) {
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
    public ApiResponse<List<MailItem>> mailList(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(mailItemRepository.findByUserIdOrderByCreatedAtDesc(user.getId()));
    }

    public record MinutesRequest(String title, String rawText) {}

    @PostMapping("/minutes/generate")
    public ApiResponse<MeetingMinutes> generateMinutes(@RequestBody MinutesRequest request, Authentication authentication) {
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
    public ApiResponse<List<MeetingMinutes>> minutesList(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(minutesRepository.findByUserIdOrderByCreatedAtDesc(user.getId()));
    }

    public record InvoiceExtractRequest(String text) {}

    @PostMapping("/invoice/extract")
    public ApiResponse<Invoice> extractInvoice(@RequestBody InvoiceExtractRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        LlmAssistantService.InvoiceResult result = llmAssistantService.extractInvoice(request.text());
        if (result == null) {
            return ApiResponse.error(400, "未能从文本中提取出发票信息，请检查内容（需包含金额）");
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
    public ApiResponse<List<Invoice>> invoiceList(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(invoiceRepository.findByUserIdOrderByInvoiceDateDescIdDesc(user.getId()));
    }
}
