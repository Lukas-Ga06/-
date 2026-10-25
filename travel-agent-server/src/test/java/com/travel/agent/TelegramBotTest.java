package com.travel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travel.agent.channel.TelegramBotService;
import com.travel.agent.channel.TelegramClient;
import com.travel.agent.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Telegram 渠道：handleUpdate 把消息交给真实 AgentEngine（规则路径，离线），
 * 回复经 TelegramClient 发出（mock 掉网络），并为 chat 自动开通本地账号。
 * 测试环境未配置 bot-token，轮询生命周期不会启动，也就不会访问任何真实网络。
 */
@SpringBootTest
@ActiveProfiles("test")
class TelegramBotTest {

    @Autowired
    private TelegramBotService botService;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private TelegramClient telegramClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void chatMessageRunsThroughEngineAndAutoProvisionsUser() throws Exception {
        long chatId = 9_100_000_000L + (long) (Math.random() * 100_000_000);
        ObjectNode update = mapper.createObjectNode();
        ObjectNode message = update.putObject("message");
        message.putObject("chat").put("id", chatId);
        message.putObject("from").put("first_name", "测试旅友");
        message.put("text", "记一下明天办签证");

        botService.handleUpdate(update);

        verify(telegramClient).sendMessage(anyString(), eq(chatId), contains("已添加备忘"));
        org.junit.jupiter.api.Assertions.assertTrue(
                userRepository.existsByUsername("tg" + chatId), "Telegram chat 应自动开通本地账号");
    }

    @Test
    void nonTextOrEmptyMessagesAreIgnored() throws Exception {
        ObjectNode update = mapper.createObjectNode();
        ObjectNode message = update.putObject("message");
        message.putObject("chat").put("id", 1_000_000_001L);
        message.put("text", "");

        botService.handleUpdate(update);

        verifyNoInteractions(telegramClient);
    }
}
