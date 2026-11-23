package com.travel.agent.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V24 登录 token 契约：token 必须携带 uid/ver；没有 uid/ver 的历史 token 一律拒绝。
 */
class JwtServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final JwtService jwtService = new JwtService(SECRET, 1);

    @Test
    @DisplayName("签发的 token 携带 uid 与 token_version，校验后原样返回")
    void issuedTokenCarriesUserIdAndVersion() {
        String token = jwtService.generate("alice", 42L, 7L);

        JwtService.TokenIdentity identity = jwtService.validate(token);

        assertThat(identity).isNotNull();
        assertThat(identity.username()).isEqualTo("alice");
        assertThat(identity.userId()).isEqualTo(42L);
        assertThat(identity.tokenVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("没有 uid/ver 的历史 token（同名重注册风险来源）被拒绝，需重新登录")
    void legacyTokenWithoutUidOrVersionIsRejected() {
        byte[] keyBytes = Base64.getDecoder().decode(SECRET);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        String legacy = Jwts.builder()
                .subject("alice")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        assertThat(jwtService.validate(legacy)).isNull();
    }

    @Test
    @DisplayName("签名被篡改或随机字符串返回 null，不抛异常")
    void tamperedOrGarbageTokenIsRejected() {
        String token = jwtService.generate("alice", 1L, 0L);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "bb" : "aa");

        assertThat(jwtService.validate(tampered)).isNull();
        assertThat(jwtService.validate("not-a-jwt")).isNull();
    }
}