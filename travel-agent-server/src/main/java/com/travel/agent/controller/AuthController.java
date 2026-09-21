package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.AuthDtos;
import com.travel.agent.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@RequestBody AuthDtos.RegisterRequest request) {
        return ApiResponse.ok(authService.register(request.getUsername(), request.getPassword(), request.getNickname()));
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.AuthResponse> login(@RequestBody AuthDtos.LoginRequest request) {
        return ApiResponse.ok(authService.login(request.getUsername(), request.getPassword()));
    }
}
