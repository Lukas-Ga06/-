package com.travel.agent;

import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.TripPhoto;
import com.travel.agent.service.PhotoService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 短期只读票据：SSE 与照片原图这类无法带请求头的场景不再暴露登录 JWT。
 */
class AccessTicketTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private PhotoService photoService;

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    }

    private String issueTicket(Account account) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/access-ticket").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("ticket").asText();
    }

    private TripPhoto uploadPhoto(Account account) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("票据测试行程");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        Trip trip = tripService.create(account.id(), request);
        return photoService.upload(trip.getId(), account.id(),
                new MockMultipartFile("file", "外滩.png", "image/png", pngBytes()));
    }

    @Test
    @DisplayName("未登录不能签发票据")
    void issuingTicketRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/access-ticket"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("票据可用于照片原图，且过期/伪造票据无效")
    void ticketCanFetchPhotoRaw() throws Exception {
        Account account = registerNew();
        TripPhoto photo = uploadPhoto(account);
        String ticket = issueTicket(account);

        mockMvc.perform(get("/api/photos/" + photo.getId() + "/raw").param("ticket", ticket))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/photos/" + photo.getId() + "/raw").param("ticket", "not-a-ticket"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/photos/" + photo.getId() + "/raw").param("ticket", ticket + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登录 JWT 不能再通过查询参数认证（防止 72 小时凭证进日志）")
    void loginJwtIsNotAcceptedInQueryString() throws Exception {
        Account account = registerNew();
        TripPhoto photo = uploadPhoto(account);

        mockMvc.perform(get("/api/photos/" + photo.getId() + "/raw").param("token", account.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("票据可以连上 SSE 流；没有凭证则被拒绝")
    void ticketOpensSseStream() throws Exception {
        Account account = registerNew();
        String ticket = issueTicket(account);
        assertThat(ticket).isNotBlank();

        mockMvc.perform(get("/api/agent/stream").param("message", "你好").param("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        mockMvc.perform(get("/api/agent/stream").param("message", "你好"))
                .andExpect(status().isUnauthorized());
    }
}
