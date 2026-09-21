package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.Memo;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.MemoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memos")
@RequiredArgsConstructor
public class MemoController {

    private final MemoService memoService;
    private final AuthService authService;

    public record AddMemoRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 500, message = "长度不能超过 500 个字符") String content) {}

    @GetMapping
    public ApiResponse<List<Memo>> list(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(memoService.list(user.getId()));
    }

    @PostMapping
    public ApiResponse<Memo> add(@Valid @RequestBody AddMemoRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(memoService.add(user.getId(), request.content()));
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<Memo> toggle(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(memoService.toggle(user.getId(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        memoService.delete(user.getId(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/open-count")
    public ApiResponse<Map<String, Long>> openCount(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(Map.of("open", memoService.openCount(user.getId())));
    }
}
