package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.PhotoDtos;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.PhotoService;
import com.travel.agent.util.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 行程照片：上传 / 列表 / 原图读取 / 删除 */
@RestController
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;
    private final AuthService authService;

    @PostMapping("/api/trips/{tripId}/photos")
    public ApiResponse<PhotoDtos.Photo> upload(@PathVariable Long tripId,
                                               @RequestParam("file") MultipartFile file,
                                               Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(PhotoDtos.Photo.from(photoService.upload(tripId, user.getId(), file)));
    }

    @GetMapping("/api/trips/{tripId}/photos")
    public ApiResponse<List<PhotoDtos.Photo>> list(
            @PathVariable Long tripId,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(Paging.slice(photoService.list(tripId, user.getId()), offset, limit).stream()
                .map(PhotoDtos.Photo::from)
                .toList());
    }

    /**
     * 原图。前端用 &lt;img&gt; 展示，而 img 不能自定义请求头，因此浏览器主路径靠 httpOnly Cookie；
     * 只有"连 Cookie 都没有"时才用 {@code ?ticket=}（用途必须为 {@code media-read}）兜底，
     * 且仅凭票据认证的请求是只读会话（票据不能挪去调写接口）。
     */
    @GetMapping("/api/photos/{id}/raw")
    public ResponseEntity<org.springframework.core.io.Resource> raw(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        PhotoService.StoredPhoto stored = photoService.fileFor(id, user.getId());
        MediaType type = stored.photo().getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(stored.photo().getContentType());
        return ResponseEntity.ok()
                .contentType(type)
                // 纵深防御：类型已由文件头推导（不会出现 svg），再用 nosniff + CSP 兜住
                // "直接把原图链接当页面打开" 的场景，避免任何脚本在该源上执行。
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("Content-Disposition", "inline")
                // 票据可能出现在 URL 里：不缓存（避免带凭证的响应留在共享缓存/磁盘），
                // 同时不发 Referer，防止 URL 经第三方链接二次泄漏。
                // 注意：这里覆盖了早期 "private, max-age=86400"，缓存换安全是有意取舍。
                .header("Cache-Control", "private, no-store")
                .header("Referrer-Policy", "no-referrer")
                .body(stored.resource());
    }

    @DeleteMapping("/api/photos/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        photoService.delete(id, user.getId());
        return ApiResponse.ok(null);
    }
}
