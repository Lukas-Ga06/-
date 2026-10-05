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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 行程照片：上传（含类型/大小校验）、读取原图、删除、以及跨用户越权 */
class TripPhotoTest extends IntegrationTestSupport {

    @Autowired
    private PhotoService photoService;

    @Autowired
    private TripService tripService;

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("照片测试行程");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private static MockMultipartFile pngFile() {
        // 最小 PNG 头，仅用于验证存储与读取链路
        byte[] bytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        return new MockMultipartFile("file", "外滩.png", "image/png", bytes);
    }

    /** 完整 PNG 文件头（服务端按文件头判类型，测试桩也必须是真的 PNG 头） */
    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    }

    @Test
    @DisplayName("上传照片后可在列表看到，并能取回原图；删除后文件也被清理")
    void uploadListFetchAndDelete() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());

        photoService.upload(trip.getId(), account.id(), pngFile());
        List<TripPhoto> photos = photoService.list(trip.getId(), account.id());
        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).getOriginalName()).isEqualTo("外滩.png");
        assertThat(photos.get(0).getContentType()).isEqualTo("image/png");

        Long photoId = photos.get(0).getId();
        MvcResult raw = mockMvc.perform(get("/api/photos/" + photoId + "/raw")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(raw.getResponse().getContentType()).contains("image/png");
        assertThat(raw.getResponse().getContentAsByteArray()).isNotEmpty();

        photoService.delete(photoId, account.id());
        assertThat(photoService.list(trip.getId(), account.id())).isEmpty();
        mockMvc.perform(get("/api/photos/" + photoId + "/raw").header("Authorization", account.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("非图片文件会被拒绝")
    void rejectsNonImage() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        MockMultipartFile text = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> photoService.upload(trip.getId(), account.id(), text))
                .isInstanceOf(com.travel.agent.exception.BusinessException.class)
                .hasMessageContaining("只支持");
    }

    @Test
    @DisplayName("客户端没给 image/* 头时，按扩展名识别并推断 MIME")
    void acceptsImageWhenContentTypeMissing() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        MockMultipartFile octet = new MockMultipartFile("file", "风景.png", "application/octet-stream",
                pngBytes());

        TripPhoto saved = photoService.upload(trip.getId(), account.id(), octet);

        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getStoredName()).endsWith(".png");
    }

    @Test
    @DisplayName("SVG 会被拒绝（否则可用 <script> 构造存储型 XSS）")
    void rejectsSvgUpload() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";
        MockMultipartFile file = new MockMultipartFile("file", "evil.svg", "image/svg+xml",
                svg.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> photoService.upload(trip.getId(), account.id(), file))
                .isInstanceOf(com.travel.agent.exception.BusinessException.class)
                .hasMessageContaining("svg");

        // 换成 .png 文件名也逃不掉：内容不是图片同样拒绝
        MockMultipartFile disguised = new MockMultipartFile("file", "evil.png", "image/svg+xml",
                svg.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> photoService.upload(trip.getId(), account.id(), disguised))
                .isInstanceOf(com.travel.agent.exception.BusinessException.class)
                .hasMessageContaining("不是可识别的图片");

        assertThat(photoService.list(trip.getId(), account.id())).isEmpty();
    }

    @Test
    @DisplayName("存储与响应类型由服务端推导，不回显客户端声明的 MIME")
    void storedContentTypeIsServerDerived() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        // 内容是真正的 PNG，但客户端把类型声明成 svg、文件名也伪装成 .png
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/svg+xml", pngBytes());

        TripPhoto saved = photoService.upload(trip.getId(), account.id(), file);
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getStoredName()).endsWith(".png");

        MvcResult raw = mockMvc.perform(get("/api/photos/" + saved.getId() + "/raw")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        // 关键断言：响应头不能带上客户端声明的 image/svg+xml
        assertThat(raw.getResponse().getContentType()).contains("image/png");
        assertThat(raw.getResponse().getContentType()).doesNotContain("svg");
        assertThat(raw.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(raw.getResponse().getHeader("Content-Security-Policy")).isNotNull();
    }

    @Test
    @DisplayName("不能查看或删除他人的行程照片")
    void otherUserCannotAccessPhoto() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        Trip trip = newTrip(owner.id());
        photoService.upload(trip.getId(), owner.id(), pngFile());
        Long photoId = photoService.list(trip.getId(), owner.id()).get(0).getId();

        mockMvc.perform(get("/api/photos/" + photoId + "/raw").header("Authorization", intruder.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/photos/" + photoId).header("Authorization", intruder.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/trips/" + trip.getId() + "/photos")
                        .file(pngFile())
                        .header("Authorization", intruder.bearer())
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("删除行程会连带清理照片记录与文件")
    void deletingTripRemovesPhotos() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account.id());
        photoService.upload(trip.getId(), account.id(), pngFile());
        Long photoId = photoService.list(trip.getId(), account.id()).get(0).getId();

        mockMvc.perform(delete("/api/trips/" + trip.getId()).header("Authorization", account.bearer()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/photos/" + photoId + "/raw").header("Authorization", account.bearer()))
                .andExpect(status().isNotFound());
    }
}
