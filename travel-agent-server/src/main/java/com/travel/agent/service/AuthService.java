package com.travel.agent.service;

import com.travel.agent.dto.AuthDtos;
import com.travel.agent.entity.User;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.UserRepository;
import com.travel.agent.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthDtos.AuthResponse register(String username, String password, String nickname) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException("用户名和密码不能为空");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException("用户名已存在");
        }
        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .nickname(nickname == null || nickname.isBlank() ? username : nickname)
                .build();
        userRepository.save(user);
        return AuthDtos.AuthResponse.of(issueToken(user), user);
    }

    public AuthDtos.AuthResponse login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(401, "用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return AuthDtos.AuthResponse.of(issueToken(user), user);
    }

    /** 签发带 uid/ver 的登录 token；版本为空按 0 处理，与 V24 的列默认值一致 */
    private String issueToken(User user) {
        long version = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
        return jwtService.generate(user.getUsername(), user.getId(), version);
    }

    /** 根据安全上下文中的用户名解析用户实体 */
    public User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(401, "用户不存在"));
    }
}
