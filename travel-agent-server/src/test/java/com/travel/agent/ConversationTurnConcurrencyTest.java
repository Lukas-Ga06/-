package com.travel.agent;

import com.travel.agent.dto.ChatDtos;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.repository.ConversationMessageRepository;
import com.travel.agent.service.ConversationService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话并发控制（V23）：两台设备同时聊同一个 conversationId 时，
 * 一轮问答（用户消息 + 它的助手回复）必须始终相邻。
 *
 * <p><b>回归的是什么</b>：V23 之前消息只有 id 顺序，没有成组标记。并发时
 * A 问、B 问、A 答、B 答会按完成先后落库，读回来是交叉的 —— 下一轮做指代消解时
 * 「上一句」指向了另一个设备的半句。这是悄悄写坏上下文，不报错、不崩溃。
 *
 * <p>断言三件事：
 * <ol>
 *   <li>并发分配出的轮次序号互不相同（CAS + 唯一键兜底，没有两轮拿到同一个号）；</li>
 *   <li>读回来永远是「问、答、问、答」，且每条回复紧跟它自己的提问（不是别人的）；</li>
 *   <li>同一条提问并发收尾两次，只会留下一条助手回复（唯一键挡住重复）。</li>
 * </ol>
 */
class ConversationTurnConcurrencyTest extends IntegrationTestSupport {

    /** 并发设备数：够撞出竞争，又不至于把连接池打满（每次写入占一条连接） */
    private static final int DEVICES = 6;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMessageRepository repository;

    @Test
    @DisplayName("并发开轮：轮次序号互不相同，且每轮问答相邻、回复不串台")
    void concurrentTurnsKeepTheirReplyAttached() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-race-" + uniqueUsername();

        ExecutorService pool = Executors.newFixedThreadPool(DEVICES);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, String> replyOfQuestion = new ConcurrentHashMap<>();
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < DEVICES; i++) {
                String question = "并发提问-" + i;
                String answer = "并发回答-" + i;
                replyOfQuestion.put(question, answer);
                futures.add(pool.submit(() -> {
                    start.await();
                    long turnSeq = conversationService.beginTurn(user.id(), conversationId, question, null);
                    conversationService.appendReply(user.id(), conversationId, turnSeq, answer, null, null);
                    return turnSeq;
                }));
            }
            start.countDown();

            List<Long> turnSeqs = new ArrayList<>();
            for (Future<Long> future : futures) {
                turnSeqs.add(future.get(30, TimeUnit.SECONDS));
            }

            // 1) 每一轮都拿到了自己的号，没有被放弃成「不分组」
            assertThat(turnSeqs)
                    .as("并发分配不应退化到 NO_TURN：%s", turnSeqs)
                    .doesNotContain(ConversationService.NO_TURN);
            assertThat(turnSeqs).as("轮次序号必须互不相同: %s", turnSeqs).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }

        // 2) 读回来是成组的问答：奇数位是提问，偶数位是它自己的回答
        List<ChatDtos.ConversationMessageView> messages =
                conversationService.messagesWithScope(user.id(), conversationId, 100).getMessages();
        assertThat(messages).as("一轮问答 = 两条消息").hasSize(DEVICES * 2);
        for (int i = 0; i < messages.size(); i += 2) {
            ChatDtos.ConversationMessageView question = messages.get(i);
            ChatDtos.ConversationMessageView answer = messages.get(i + 1);
            assertThat(question.getRole())
                    .as("每轮必须以用户提问开头: 第 %d 条", i)
                    .isEqualTo(ConversationMessage.ROLE_USER);
            assertThat(answer.getRole())
                    .as("提问之后必须紧跟它自己的回复: 第 %d 条", i + 1)
                    .isEqualTo(ConversationMessage.ROLE_ASSISTANT);
            assertThat(answer.getContent())
                    .as("第 %d 条回复串台了（回答与提问不匹配）: 顺序=%s", i + 1,
                            messages.stream().map(ChatDtos.ConversationMessageView::getContent).toList())
                    .isEqualTo(replyOfQuestion.get(question.getContent()));
        }
    }

    @Test
    @DisplayName("同一轮重复收尾：唯一键挡住第二条助手回复，不覆盖也不追加")
    void duplicateReplyIsIgnored() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-dup-" + uniqueUsername();

        long turnSeq = conversationService.beginTurn(user.id(), conversationId, "记一下明天办签证", null);
        conversationService.appendReply(user.id(), conversationId, turnSeq, "已记录", null, null);
        // 同一轮再收尾一次（客户端重连 / 引擎重试）：不能出现两条「助手答」
        conversationService.appendReply(user.id(), conversationId, turnSeq, "已记录", null, null);

        assertThat(conversationService.recent(user.id(), conversationId))
                .extracting(ConversationMessage::getRole)
                .containsExactly(ConversationMessage.ROLE_USER, ConversationMessage.ROLE_ASSISTANT);
        assertThat(repository.maxTurnSeq(user.id(), conversationId))
                .as("重复收尾不应推进轮次号")
                .isEqualTo(turnSeq);
    }

    @Test
    @DisplayName("单轮对话（无 conversationId / 空内容）不分配轮次号，也不落库")
    void singleTurnConversationStoresNothing() throws Exception {
        Account user = registerNew();
        String conversationId = "conv-single-" + uniqueUsername();

        assertThat(conversationService.beginTurn(user.id(), null, "随便聊聊", null))
                .isEqualTo(ConversationService.NO_TURN);
        assertThat(conversationService.beginTurn(user.id(), "  ", "随便聊聊", null))
                .isEqualTo(ConversationService.NO_TURN);
        // 空内容不写（历史只用于指代消解，空消息没有任何信息量）
        assertThat(conversationService.beginTurn(user.id(), conversationId, "  ", null))
                .isEqualTo(ConversationService.NO_TURN);
        // 没有轮次号的回复同样不落库
        conversationService.appendReply(user.id(), conversationId, ConversationService.NO_TURN, " ", null, null);

        assertThat(repository.findRecent(user.id(), conversationId, PageRequest.of(0, 10)))
                .as("单轮 / 空内容不应在会话记忆里留下行")
                .isEmpty();
    }
}