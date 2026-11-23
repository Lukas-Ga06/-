package com.travel.agent;

import com.travel.agent.service.ConversationService;
import com.travel.agent.service.ConversationTurnWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置键绑定：验证 {@code travel.conversation.*} 真的接上了 {@link ConversationService}。
 *
 * <p>为什么单独一个测试类：主用例（ConversationMemoryTest）跑的是默认配置，
 * 属性键写错也会回落默认值 10/7 而"看起来通过"。这里用非默认值（4/3）探针，
 * 键写错时 historyLimit() 会拿到 10，测试立刻失败。
 *
 * <p>用 {@code @DataJpaTest} 而不是 {@code @SpringBootTest}：数据源由测试框架换成独立内嵌库，
 * 不碰主测试库，也不需要 LLM/安全/外部服务，跑得更快。
 * 该切片不带 {@code @EnableScheduling}，所以每日清理任务不会在这里被注册。
 *
 * <p>V23 起 {@code ConversationService} 还依赖 {@link ConversationTurnWriter}（轮次序号的独立事务写入器），
 * 切片不会自动带上它 —— 不显式导入的话上下文直接起不来，测的还是「配置绑定」却死在装配上。
 */
@DataJpaTest(properties = {
        "travel.conversation.history-limit=4",
        "travel.conversation.retention-days=3"
})
@Import({ConversationService.class, ConversationTurnWriter.class})
@ActiveProfiles("test")
class ConversationConfigTest {

    @Autowired
    private ConversationService conversationService;

    @Test
    @DisplayName("travel.conversation.history-limit / retention-days 绑定到服务实例")
    void conversationPropertiesAreBound() {
        assertThat(conversationService.historyLimit()).isEqualTo(4);
        assertThat(conversationService.retentionDays()).isEqualTo(3);
    }
}