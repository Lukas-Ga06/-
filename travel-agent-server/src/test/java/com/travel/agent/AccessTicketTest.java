package com.travel.agent;

import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.TripPhoto;
import com.travel.agent.security.AccessTicketService;
import com.travel.agent.security.JwtService;
import com.travel.agent.service.PhotoService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 短期只读票据：SSE 与照片原图这类无法带请求头的场景不再暴露登录 JWT。
 *
 * <p>覆盖点：用途（aud）隔离、只读路径白名单、旧票据兼容、响应头防泄漏。
 */
class AccessTicketTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private PhotoService photoService;

    @Autowired
    private JwtService jwtService;

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    }

    /** 指定用途签发票据 */
    private String issueTicket(Account account, String purpose) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/access-ticket").param("purpose", purpose)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("ticket").asText();
    }

    /** 不指定用途 —— 默认 agent-stream（SSE 用的那种） */
    private String issueDefaultTicket(Account account) throws Exception {
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

    /**
     * 手工构造 v1 格式票据（本次改动前签发的形态）：{@code media-read|expiresAt|username}。
     * 用来验证"旧票据没有 aud 段"的兼容行为。
     */
    private String legacyTicket(String username) throws Exception {
        SecretKey key = jwtService.deriveKey("media-read");
        String payload = "media-read|" + (Instant.now().getEpochSecond() + 300) + "|" + username;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
        return encoded + "." + signature;
    }

    @Test
    @DisplayName("未登录不能签发票据")
    void issuingTicketRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/access-ticket"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("purpose 白名单：非法用途 400，合法用途回显在票据响应里")
    void purposeIsWhitelisted() throws Exception {
        Account account = registerNew();

        mockMvc.perform(post("/api/access-ticket").param("purpose", "delete-everything")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isBadRequest());

        MvcResult result = mockMvc.perform(post("/api/access-ticket").param("purpose", "media-read")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(result).path("data").path("purpose").asText()).isEqualTo("media-read");

        // 不传 purpose 默认 agent-stream（SSE）
        MvcResult defaulted = mockMvc.perform(post("/api/access-ticket")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(defaulted).path("data").path("purpose").asText()).isEqualTo("agent-stream");
    }

    @Test
    @DisplayName("media-read 票据可用于照片原图；用途不符/伪造票据一律无效，响应不缓存不发 Referer")
    void ticketCanFetchPhotoRaw() throws Exception {
        Account account = registerNew();
        TripPhoto photo = uploadPhoto(account);
        String ticket = issueTicket(account, AccessTicketService.PURPOSE_MEDIA_READ);

        mockMvc.perform(get("/api/photos/" + photo.getId() + "/raw").param("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));

        // 用途不符：SSE 票据不能读照片
        String streamTicket = issueDefaultTicket(account);
        mockMvc.perform(get("/api/photos/" + photo.getId() + "/raw").param("ticket", streamTicket))
                .andExpect(status().isUnauthorized());

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
    @DisplayName("票据不能用于写接口：POST /api/memos 一律 401")
    void ticketCannotPostMemo() throws Exception {
        Account account = registerNew();
        String ticket = issueDefaultTicket(account);

        mockMvc.perform(post("/api/memos").param("ticket", ticket)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"票据不该能写\"}"))
                .andExpect(status().isUnauthorized());

        // 对照：同一账号正常登录态可以写，证明上面的 401 是"票据被收窄"而不是接口本身不可用
        mockMvc.perform(post("/api/memos").header("Authorization", account.bearer())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"正常登录可以写\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("票据不能访问白名单之外的业务路径：GET /api/expenses/overview 401")
    void ticketCannotReadUnrelatedEndpoint() throws Exception {
        Account account = registerNew();
        String ticket = issueDefaultTicket(account);

        mockMvc.perform(get("/api/expenses/overview").param("ticket", ticket))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/trips").param("ticket", ticket))
                .andExpect(status().isUnauthorized());

        // 对照：登录态仍然可读
        mockMvc.perform(get("/api/expenses/overview").header("Authorization", account.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("票据不能自我续期：POST /api/access-ticket 401")
    void ticketCannotRenewItself() throws Exception {
        Account account = registerNew();
        String ticket = issueDefaultTicket(account);

        mockMvc.perform(post("/api/access-ticket").param("ticket", ticket))
                .andExpect(status().isUnauthorized());

        // 对照：Bearer 登录态可正常签发票据
        mockMvc.perform(post("/api/access-ticket").header("Authorization", account.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("票据 TTL 默认 300 秒（URL 凭证宁短勿长）")
    void ticketTtlDefaultsToFiveMinutes() throws Exception {
        Account account = registerNew();
        MvcResult result = mockMvc.perform(post("/api/access-ticket").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(result).path("data").path("expiresInSeconds").asLong()).isEqualTo(300L);
    }

    @Test
    @DisplayName("agent-stream 票据可以连上 SSE 流；没有凭证、或用途不符都被拒绝")
    void ticketOpensSseStream() throws Exception {
        Account account = registerNew();
        String ticket = issueDefaultTicket(account);
        assertThat(ticket).isNotBlank();

        mockMvc.perform(get("/api/agent/stream").param("message", "你好").param("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(request().asyncStarted());

        mockMvc.perform(get("/api/agent/stream").param("message", "你好"))
                .andExpect(status().isUnauthorized());

        // 用途不符：照片票据不能开 SSE
        String photoTicket = issueTicket(account, AccessTicketService.PURPOSE_MEDIA_READ);
        mockMvc.perform(get("/api/agent/stream").param("message", "你好").param("ticket", photoTicket))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("旧格式票据（没有 aud）按 agent-stream 兼容：能开 SSE，但不能读照片")
    void legacyTicketWithoutAudFallsBackToAgentStream() throws Exception {
        Account account = registerNew();
        TripPhoto photo = uploadPhoto(account);
        String legacy = legacyTicket(account.username());

        mockMvc.perform(get("/api/agent/stream").param("message", "你好").param("ticket", legacy))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        mockMvc.perform(get("/api/photos/" + photo.getId() + "/raw").param("ticket", legacy))
                .andExpect(status().isUnauthorized());
    }
}