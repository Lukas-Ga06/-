package com.travel.agent.service;

import com.travel.agent.dto.ChatDtos;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.entity.ConversationMeta;
import com.travel.agent.repository.ConversationMessageRepository;
import com.travel.agent.repository.ConversationMetaRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话记忆：按「用户 + conversationId」存取最近的对话消息。
 *
 * conversationId 由前端生成并随请求携带，跨页面切换保持不变（sessionStorage）；
 * 服务端一切查询都以 userId 为准做归属隔离，客户端传什么都拿不到别人的历史。
 *
 * <h2>边界（重要，写文档时照抄这段）</h2>
 * 历史<b>只喂给 LLM 意图识别做指代消解</b>（{@code IntentAgent.renderHistory}），
 * <b>不进入规划产物</b>：{@code PlannerAgent} / {@code StructuredPlanner} 从不读取
 * {@code AgentContext.getHistory()}，行程/预算/日程的内容只由当前这一条消息 + 行程上下文决定。
 * 也就是说「最近 N 条消息」影响的是"听懂你说的是哪件事"，不是"计划长什么样"。
 *
 * <h2>条数语义</h2>
 * {@code travel.conversation.history-limit} 的 N 是<b>消息条数</b>（一轮问答 = 2 条：
 * 用户 + 助理），默认 10 条 ≈ 5 轮，不是 10 轮。{@code recent()} 一律「取最新 N 条、按时间正序返回」，
 * 条数直接进查询（不再有「先取 20 再截 10」这类隐式魔法数）。
 *
 * <h2>保留期与清理</h2>
 * {@code travel.conversation.retention-days} 默认 7 天。清理有两条路：
 * <ul>
 *   <li>快路径：{@link #beginTurn} 成功落库后每小时最多一次顺手清理（会话活跃时及时回收）；</li>
 *   <li>兜底：{@link #purgeDaily()} {@code @Scheduled} 每天跑一次（闲置账号没人写就不会触发快路径，
 *       只靠它会让过期数据无限堆积）。</li>
 * </ul>
 * 两条路共用 {@link #purgeExpired()} —— 它不依赖调度器，测试/运维可直接调用。
 *
 * <h2>并发（V23）</h2>
 * 一轮问答（用户消息 + 助手回复）共用一个 {@code turn_seq}。分配是 compare-and-set：
 * 取该会话当前最大序号 +1 后直接插入，撞唯一键就换号重试（{@link ConversationTurnWriter}）。
 * 两台设备同时聊同一个 conversationId 时，后到的那一轮顺延到下一个号，
 * 两轮各自的问答始终相邻 —— 读回来不会再出现「A 问、B 问、A 答、B 答」的交叉。
 * 顺序口径统一为 {@code COALESCE(turn_seq, 0), createdAt, id}：存量行（无序号）排在所有新轮次之前。
 */
@Service
@Slf4j
public class ConversationService {

    /** 最近的会话消息条数默认值（条数，不是轮数；一轮问答占 2 条） */
    public static final int DEFAULT_HISTORY_LIMIT = 10;
    /** 会话消息保留天数默认值 */
    public static final int DEFAULT_RETENTION_DAYS = 7;
    /** 清理节流：最多每小时做一次全表过期清理 */
    private static final long PURGE_INTERVAL_MS = 60 * 60 * 1000;
    /** 会话标题的最大字数；超出截断加省略号（列表是单行展示，标题不能撑破布局） */
    private static final int TITLE_MAX_LENGTH = 40;
    /** 没有任何用户消息时的兜底标题 */
    private static final String DEFAULT_TITLE = "新会话";
    /**
     * 轮次序号的分配尝试上限（V23）。
     *
     * <p>为什么不是一个很小的数：并发 N 台设备同时开口时，第一轮会有 N-1 个请求撞键
     * （大家都读到了同一个最大值），之后每轮继续收敛，最坏需要 O(N) 次。上限给到 20，
     * 覆盖「一个账号在多端同时刷」的现实规模；真到上限就退化成「不分组写入」——
     * 宁可少一个分组标记，也不丢用户的消息。
     */
    private static final int TURN_ALLOCATION_ATTEMPTS = 20;
    /** 撞键后的抖动上限（毫秒）：让同时重试的请求错开，避免一直踩着同一个号 */
    private static final int TURN_RETRY_JITTER_MS = 4;
    /** 未分配到轮次序号时的返回值（单轮对话 / 分配失败）：回复照旧落库，只是不带分组 */
    public static final long NO_TURN = -1L;

    private final ConversationMessageRepository repository;
    private final ConversationMetaRepository metaRepository;
    private final ConversationTurnWriter turnWriter;
    /** 喂给模型的最近消息条数（travel.conversation.history-limit，非法值已回落默认） */
    private final int historyLimit;
    /** 消息保留天数（travel.conversation.retention-days，非法值已回落默认） */
    private final int retentionDays;
    private final AtomicLong nextPurgeAt = new AtomicLong();

    public ConversationService(ConversationMessageRepository repository,
                               ConversationMetaRepository metaRepository,
                               ConversationTurnWriter turnWriter,
                               @Value("${travel.conversation.history-limit:10}") int historyLimit,
                               @Value("${travel.conversation.retention-days:7}") int retentionDays) {
        this.repository = repository;
        this.metaRepository = metaRepository;
        this.turnWriter = turnWriter;
        this.historyLimit = positiveOr(historyLimit, DEFAULT_HISTORY_LIMIT, "travel.conversation.history-limit");
        this.retentionDays = positiveOr(retentionDays, DEFAULT_RETENTION_DAYS, "travel.conversation.retention-days");
    }

    /** 生效中的历史条数上限（配置解析后的值，便于排查与测试） */
    public int historyLimit() {
        return historyLimit;
    }

    /** 生效中的保留天数（配置解析后的值，便于排查与测试） */
    public int retentionDays() {
        return retentionDays;
    }

    /**
     * 最近一段对话：取最新 {@code historyLimit} 条，按时间正序（旧 → 新）返回。
     * 历史为空返回空列表；不足 limit 时全量返回；conversationId 为空视为单轮对话（不读历史）。
     *
     * <p>条数上限直接下推到查询（{@link PageRequest}），避免先拉一批再在内存里截断。
     * 排序带上 id 兜底：同一毫秒写入的若干条消息也要有稳定顺序，否则"最新 N 条"会漂。
     */
    @Transactional(readOnly = true)
    public List<ConversationMessage> recent(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return Collections.emptyList();
        }
        ConversationMessage probe = ConversationMessage.builder()
                .userId(userId)
                .conversationId(conversationId)
                .build();
        // 用 Example 做「userId + conversationId 精确匹配」：不新增 repository 方法，
        // 保持 repository 层签名不变。若后续在 ConversationMessageRepository 增加
        // findByUserIdAndConversationId(..., Pageable)，这里可直接换成显式方法。
        Pageable latestPage = PageRequest.of(0, historyLimit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        List<ConversationMessage> latest =
                new ArrayList<>(repository.findAll(Example.of(probe), latestPage).getContent());
        Collections.reverse(latest);
        return latest;
    }

    /**
     * 最近会话列表（§8.6）：只列**当前用户**的会话，按最后活动时间倒序。
     *
     * <p>标题取「该会话首条用户消息」的前 {@value #TITLE_MAX_LENGTH} 字 —— 用户开口的第一句
     * 最能说明这段对话是关于什么的；没有用户消息（理论上只可能是异常数据）时回落为「新会话」。
     * 只返回「继续对话」需要的字段，不返回正文，避免列表接口变成历史导出。
     *
     * <p>边界：会话记忆只保留 {@code retention-days} 天，所以列表天然只覆盖保留期内的对话；
     * 过期会话不会出现在这里，也不需要前端做过期判断。
     */
    @Transactional(readOnly = true)
    public List<ChatDtos.ConversationSummary> summaries(Long userId, int limit) {
        if (userId == null || limit <= 0) {
            return List.of();
        }
        List<ConversationMessageRepository.ConversationSummaryView> rows =
                repository.summarize(userId, PageRequest.of(0, limit));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = rows.stream().map(ConversationMessageRepository.ConversationSummaryView::getConversationId).toList();
        // 标题：一次 IN 查询取回首条用户消息，按会话去重（不在循环里逐条查，避免 N+1）
        Map<String, String> titles = new LinkedHashMap<>();
        for (ConversationMessage message : repository.findFirstOfRole(userId, ConversationMessage.ROLE_USER, ids)) {
            titles.putIfAbsent(message.getConversationId(), titleOf(message.getContent()));
        }
        // 用户显式改过的名字（V23）优先于推导标题：一次 IN 查询取回，不在循环里查
        Map<String, String> renamed = new LinkedHashMap<>();
        for (ConversationMeta meta : metaRepository.findByUserIdAndConversationIdIn(userId, ids)) {
            String assigned = blankToNull(meta.getTitle());
            if (assigned != null) {
                renamed.put(meta.getConversationId(), assigned);
            }
        }
        List<ChatDtos.ConversationSummary> result = new ArrayList<>(rows.size());
        for (ConversationMessageRepository.ConversationSummaryView row : rows) {
            result.add(ChatDtos.ConversationSummary.builder()
                    .conversationId(row.getConversationId())
                    .title(renamed.getOrDefault(row.getConversationId(),
                            titles.getOrDefault(row.getConversationId(), DEFAULT_TITLE)))
                    .lastMessageAt(row.getLastMessageAt() == null ? null : row.getLastMessageAt().toString())
                    .messageCount(row.getMessageCount() == null ? 0L : row.getMessageCount())
                    // coalesce 折叠成空串后在这里还原成 null：null 表示「未绑定」，不能伪装成某个作用域
                    .scope(blankToNull(row.getScope()))
                    .build());
        }
        return result;
    }

    /**
     * 某个会话最近 {@code limit} 条消息，按时间正序返回（旧 → 新），用于「继续对话」回填。
     * 不属于当前用户的 conversationId 返回空列表（不抛 404，避免用它探测他人会话是否存在）。
     */
    @Transactional(readOnly = true)
    public List<ChatDtos.ConversationMessageView> messages(Long userId, String conversationId, int limit) {
        return messagesWithScope(userId, conversationId, limit).getMessages();
    }

    /**
     * 「继续对话」回填的完整结果：消息 + 这段会话的作用域。
     *
     * <p>作用域取该会话<b>最近一条消息</b>携带的值 —— 与列表接口同一口径，
     * 前端据此把 {@code trip:<id>} 还原成行程绑定，续聊时才不会丢「我现在在这个行程里」这个前提。
     * 别人的/不存在的会话返回空消息 + null 作用域（同上，不泄露是否存在）。
     */
    @Transactional(readOnly = true)
    public ChatDtos.ConversationMessagesView messagesWithScope(Long userId, String conversationId, int limit) {
        if (userId == null || conversationId == null || conversationId.isBlank() || limit <= 0) {
            return ChatDtos.ConversationMessagesView.builder()
                    .conversationId(conversationId)
                    .messages(List.of())
                    .build();
        }
        List<ConversationMessage> latest = new ArrayList<>(
                repository.findRecent(userId, conversationId, PageRequest.of(0, limit)));
        Collections.reverse(latest);
        String scope = latest.isEmpty()
                ? null
                : latest.get(latest.size() - 1).getScope();
        return ChatDtos.ConversationMessagesView.builder()
                .conversationId(conversationId)
                .scope(scope)
                .messages(latest.stream()
                        .map(message -> ChatDtos.ConversationMessageView.builder()
                                .role(message.getRole())
                                .content(message.getContent())
                                .createdAt(message.getCreatedAt() == null ? null : message.getCreatedAt().toString())
                                .build())
                        .toList())
                .build();
    }

    private static String titleOf(String content) {
        if (content == null || content.isBlank()) {
            return DEFAULT_TITLE;
        }
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= TITLE_MAX_LENGTH
                ? oneLine
                : oneLine.substring(0, TITLE_MAX_LENGTH) + "…";
    }

    /** 空串与空白一律折叠成 null：「未绑定」只有一个表示法，避免 '' 与 null 两套语义打架 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 开启一轮问答（V23）：分配该会话的下一个轮次序号，并落下本轮的用户消息。
     *
     * <p><b>为什么是 compare-and-set</b>：序号没有独立的计数器行，靠「读当前最大值 +1 后直接插入」
     * 分配，由唯一键 {@code (user_id, conversation_id, turn_seq, role)} 兜底并发。
     * 两台设备几乎同时开口时，后到的那次插入撞键 → 重读最大值 → 顺延到下一个号。
     * 结果是两轮各自拿到互不相同的号，读回来不会互相穿插。
     *
     * <p>超过尝试上限时退化成「不带序号的单条写入」并返回 {@link #NO_TURN}：
     * 分组标记丢了可以接受，用户的这句话不能丢。
     *
     * @return 本轮序号；{@link #NO_TURN} 表示未分组（单轮对话 / 分配失败）
     */
    public long beginTurn(Long userId, String conversationId, String content, String scope) {
        if (userId == null || conversationId == null || conversationId.isBlank()
                || content == null || content.isBlank()) {
            return NO_TURN;
        }
        for (int attempt = 0; attempt < TURN_ALLOCATION_ATTEMPTS; attempt++) {
            long turnSeq = repository.maxTurnSeq(userId, conversationId) + 1;
            if (turnWriter.insertOnce(messageOf(userId, conversationId, ConversationMessage.ROLE_USER,
                    content, null, scope, turnSeq))) {
                purgeExpiredIfDue();
                return turnSeq;
            }
            // 抖动后再读：几台设备同时重试时，不加抖动会一直踩着同一个号反复撞
            if (!backoffBeforeRetry(attempt)) {
                break;
            }
        }
        log.warn("会话轮次分配连续 {} 次撞键，本轮不分组写入: user={} conversation={}",
                TURN_ALLOCATION_ATTEMPTS, userId, conversationId);
        turnWriter.insertOnce(messageOf(userId, conversationId, ConversationMessage.ROLE_USER,
                content, null, scope, null));
        return NO_TURN;
    }

    /**
     * 落本轮的助手回复：与 {@link #beginTurn} 共用同一个轮次序号，两行因此始终相邻。
     *
     * <p>撞唯一键说明这一轮已经有回复了（同一轮被重复收尾），直接忽略——
     * 不覆盖、不追加第二条，历史里不会出现两个「助手答」。
     */
    public void appendReply(Long userId, String conversationId, long turnSeq, String content, String intent,
                            String scope) {
        if (userId == null || conversationId == null || conversationId.isBlank()
                || content == null || content.isBlank()) {
            return;
        }
        boolean written = turnWriter.insertOnce(messageOf(userId, conversationId,
                ConversationMessage.ROLE_ASSISTANT, content, intent, scope,
                turnSeq == NO_TURN ? null : turnSeq));
        if (!written) {
            log.debug("本轮回复已存在，跳过重复收尾: user={} conversation={} turn={}",
                    userId, conversationId, turnSeq);
            return;
        }
        purgeExpiredIfDue();
    }

    private static ConversationMessage messageOf(Long userId, String conversationId, String role, String content,
                                                 String intent, String scope, Long turnSeq) {
        return ConversationMessage.builder()
                .userId(userId)
                .conversationId(conversationId)
                .role(role)
                .content(truncate(content))
                .intent(intent)
                .scope(blankToNull(scope))
                .turnSeq(turnSeq)
                .build();
    }

    /**
     * 撞键后的短抖动：让同时重试的请求错开。返回 false 表示线程被中断，调用方应停止重试
     * （恢复中断标志而不是把它吃掉）。
     */
    private static boolean backoffBeforeRetry(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, TURN_RETRY_JITTER_MS + 1) + (long) attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 开新会话：清掉该用户在这个会话里的历史 */
    @Transactional
    public void clear(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        repository.deleteByUserIdAndConversationId(userId, conversationId);
        metaRepository.deleteByUserIdAndConversationId(userId, conversationId);
    }

    /**
     * 重命名一段会话（V23）：只写 {@code conversation_meta}，不动消息。
     *
     * <p>空标题（null / 空白）表示<b>恢复自动标题</b> —— 删掉覆盖行，
     * 列表回到「首条用户消息」的推导标题。这样「改错了」有退路，不必依赖前端清空输入框的语义猜测。
     *
     * @return 生效的标题；null 表示已恢复自动标题（调用方按推导标题展示即可）
     */
    @Transactional
    public String rename(Long userId, String conversationId, String title) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return null;
        }
        String assigned = titleOf(title);
        boolean restoreAuto = title == null || title.isBlank();
        ConversationMeta meta = metaRepository.findByUserIdAndConversationId(userId, conversationId)
                .orElse(null);
        if (restoreAuto) {
            if (meta != null) {
                metaRepository.delete(meta);
            }
            return null;
        }
        if (meta == null) {
            metaRepository.save(ConversationMeta.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .title(assigned)
                    .build());
        } else {
            meta.setTitle(assigned);
            metaRepository.save(meta);
        }
        return assigned;
    }

    /**
     * 删除一段会话（V23）：消息与元数据一起删，按 userId 隔离，别人的会话删不动。
     *
     * <p>已过保留期的会话本来就没有行，删除同样返回 false（幂等：重复删除不报错）。
     *
     * @return true = 真的删掉了消息或元数据；false = 本来就没有
     */
    @Transactional
    public boolean delete(Long userId, String conversationId) {
        if (userId == null || conversationId == null || conversationId.isBlank()) {
            return false;
        }
        long messages = repository.deleteByUserIdAndConversationId(userId, conversationId);
        long metas = metaRepository.deleteByUserIdAndConversationId(userId, conversationId);
        return messages > 0 || metas > 0;
    }

    /**
     * 删除早于 {@code now - retentionDays} 的会话消息，返回删除条数。
     *
     * <p>这是清理的唯一实现：定时任务、写入时快路径、测试都调它，
     * 所以「保留 N 天」的语义只有一处，不会因为只测调度器而漏掉。
     * 之所以是 public 而不是包可见：测试类在 {@code com.travel.agent} 包下（父包），
     * 包可见方法它调不到；该方法不属于对外 HTTP 契约，仅服务内部与测试使用。
     */
    @Transactional
    public int purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int removed = (int) repository.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("清理过期会话消息 {} 条（保留 {} 天，早于 {}）", removed, retentionDays, cutoff);
        }
        return removed;
    }

    /**
     * 每日兜底清理：闲置账号再久没人写对话，也会在第二天被回收。
     * 时区沿用项目惯例 {@code travel.timezone}（默认 Asia/Shanghai），与「今天/明天」等业务日期口径一致；
     * 放在凌晨低峰，且独立事务：清理失败只记日志，不影响任何请求。
     */
    @Scheduled(cron = "0 30 4 * * *", zone = "${travel.timezone:Asia/Shanghai}")
    @Transactional
    public void purgeDaily() {
        try {
            purgeExpired();
        } catch (Exception e) {
            // 定时任务失败不能让调度线程抛出；明天再试，写入侧每小时快路径仍会兜住活跃账号
            log.warn("定时清理过期会话消息失败: {}", e.getMessage());
        }
    }

    private static String truncate(String content) {
        return content.length() <= ConversationMessage.MAX_CONTENT_LENGTH
                ? content
                : content.substring(0, ConversationMessage.MAX_CONTENT_LENGTH);
    }

    /**
     * 写入时的小快路径：每小时最多一次。
     * 注意它只覆盖「正在聊天」的账号，不能作为唯一清理手段（见类注释）。
     */
    private void purgeExpiredIfDue() {
        long now = System.currentTimeMillis();
        long current = nextPurgeAt.get();
        if (now < current || !nextPurgeAt.compareAndSet(current, now + PURGE_INTERVAL_MS)) {
            return;
        }
        try {
            purgeExpired();
        } catch (Exception e) {
            // 清理失败不影响主流程，下一小时再试
            log.warn("过期会话消息清理失败: {}", e.getMessage());
        }
    }

    /** 非法配置（≤0 / 非数字）回落默认并告警：宁可保留能力，也不要因为改错配置把记忆关成 0 条 */
    private static int positiveOr(int configured, int fallback, String key) {
        if (configured > 0) {
            return configured;
        }
        log.warn("配置 {}={} 非法（要求正整数），回落默认值 {}", key, configured, fallback);
        return fallback;
    }
}