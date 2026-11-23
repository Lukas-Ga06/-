package com.travel.agent.service;

import com.travel.agent.entity.User;
import com.travel.agent.rag.VectorStore;
import com.travel.agent.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * V24：账号注销提交后必须重建内存向量索引，否则已删用户的个人知识块会留在进程内，
 * 直到下次重启才消失（接口已 401，但严格「无残留」不成立）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountDeletionVectorReloadTest {

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private VectorStore vectorStore;

    @Test
    @DisplayName("注销事务提交后调用 vectorStore.reload()，且只调用一次")
    void reloadsVectorStoreAfterCommit() {
        User user = userRepository.save(User.builder()
                .username("reload_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .password(passwordEncoder.encode("pw123456"))
                .nickname("reload")
                .build());

        // 启动时的 ApplicationReadyEvent reload 不计入本次断言
        clearInvocations(vectorStore);

        accountDeletionService.deleteAccount(user.getId(), user.getUsername());

        verify(vectorStore, times(1)).reload();
    }
}