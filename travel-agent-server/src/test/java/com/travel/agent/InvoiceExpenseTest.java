package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Invoice;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.InvoiceRepository;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 发票一键记账：分类映射、日期金额、防重复、归属校验 */
class InvoiceExpenseTest extends IntegrationTestSupport {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private TripService tripService;

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("发票记账测试");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private Invoice newInvoice(long userId, String category, String title) {
        return invoiceRepository.save(Invoice.builder()
                .userId(userId)
                .title(title)
                .vendor("中国铁路")
                .category(category)
                .amount(BigDecimal.valueOf(120))
                .invoiceDate(LocalDate.of(2026, 10, 10))
                .rawText("价税合计 120")
                .build());
    }

    private JsonNode postExpense(Account account, long invoiceId, long tripId, int expectStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/office/invoice/" + invoiceId + "/expense")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripId\":" + tripId + ",\"paymentMethod\":\"支付宝\"}"))
                .andExpect(status().is(expectStatus))
                .andReturn();
        return body(result);
    }

    @Test
    @DisplayName("发票生成行程支出：金额、分类、日期与备注正确，并标记已记账")
    void invoiceBecomesExpense() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        Invoice invoice = newInvoice(account.id(), "交通", "差旅发票");

        JsonNode body = postExpense(account, invoice.getId(), trip.getId(), 200);
        JsonNode data = body.path("data");

        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("120");
        assertThat(data.path("category").asText()).isEqualTo("交通");
        assertThat(data.path("spentAt").asText()).isEqualTo("2026-10-10");
        assertThat(data.path("note").asText()).contains("发票").contains("差旅发票");
        assertThat(data.path("paymentMethod").asText()).isEqualTo("支付宝");

        Invoice reloaded = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(reloaded.getExpenseId()).isEqualTo(data.path("id").asLong());
        assertThat(reloaded.getTripId()).isEqualTo(trip.getId());
    }

    @Test
    @DisplayName("同一张发票不能重复记账")
    void duplicateIsRejected() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        Invoice invoice = newInvoice(account.id(), "餐饮", "餐费发票");

        postExpense(account, invoice.getId(), trip.getId(), 200);
        JsonNode second = postExpense(account, invoice.getId(), trip.getId(), 409);

        assertThat(second.path("message").asText()).contains("已经记过账");
    }

    @Test
    @DisplayName("办公类发票归入「其他」分类")
    void officeCategoryMapsToOther() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        Invoice invoice = newInvoice(account.id(), "办公", "办公用品");

        JsonNode data = postExpense(account, invoice.getId(), trip.getId(), 200).path("data");
        assertThat(data.path("category").asText()).isEqualTo("其他");
    }

    @Test
    @DisplayName("不能给他人发票记账，也不能记到他人行程")
    void ownershipIsEnforced() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        Trip ownerTrip = newTrip(owner.id());
        Invoice invoice = newInvoice(owner.id(), "交通", "差旅发票");

        postExpense(intruder, invoice.getId(), ownerTrip.getId(), 404);
        postExpense(owner, invoice.getId(), ownerTrip.getId(), 200);
    }
}
