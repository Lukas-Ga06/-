package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(length = 100)
    private String nickname;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 登录 token 的版本号（V24）：JWT 写入 {@code ver}，认证时要求与库中一致。
     * 版本 +1 即可让该用户已签发的全部 token 立即失效（注销、改密、踢下线共用）。
     * DDL 默认 0；这里再兜一次，避免历史代码路径绕过数据库默认值。
     */
    @Column(name = "token_version", nullable = false)
    private Long tokenVersion;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (tokenVersion == null) {
            tokenVersion = 0L;
        }
    }
}
