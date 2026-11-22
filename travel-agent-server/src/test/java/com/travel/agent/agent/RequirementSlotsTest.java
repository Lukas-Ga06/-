package com.travel.agent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-01：需求槽位的确定性解析。
 *
 * <p>只测「从一句话里解析出什么」，不涉及追问策略（那部分在
 * {@code RequirementFollowUpTest} 里跑真实引擎）。
 */
class RequirementSlotsTest {

    @Test
    @DisplayName("一次说全：三个人下周六去北京两天 → 目的地/天数/人数/日期都有，预算留空")
    void parsesCompleteSentence() {
        RequirementSlots slots = RequirementSlots.fromMessage("三个人下周六去北京两天");

        assertThat(slots.destination()).isEqualTo("北京");
        assertThat(slots.days()).isEqualTo(2);
        assertThat(slots.partySize()).isEqualTo(3);
        assertThat(slots.startDate()).isNotNull();
        assertThat(slots.budget()).isNull();
        assertThat(slots.missingRequired()).isEmpty();
    }

    @Test
    @DisplayName("常规句式：帮我规划上海三日游，2人，预算3000元")
    void parsesClassicSentence() {
        RequirementSlots slots = RequirementSlots.fromMessage("帮我规划上海三日游，2人，预算3000元");

        assertThat(slots.destination()).isEqualTo("上海");
        assertThat(slots.days()).isEqualTo(3);
        assertThat(slots.partySize()).isEqualTo(2);
        assertThat(slots.budget()).isEqualByComparingTo("3000");
        assertThat(slots.missingRequired()).isEmpty();
    }

    @Test
    @DisplayName("什么都没说就什么都没解析到（目的地/天数/人数都缺）")
    void parsesNothingFromVagueRequest() {
        RequirementSlots slots = RequirementSlots.fromMessage("帮我规划行程");

        assertThat(slots.destination()).isNull();
        assertThat(slots.days()).isNull();
        assertThat(slots.partySize()).isNull();
        assertThat(slots.missingRequired()).containsExactly("destination", "days", "partySize");
    }

    @Test
    @DisplayName("结构化表单的「成人 N，儿童 M」按人头相加")
    void parsesFormCompanions() {
        assertThat(MessageParser.extractPartySize("同行人员：带父母出行（成人 2，儿童 1）")).isEqualTo(3);
        assertThat(MessageParser.extractPartySize("同行人员：独自出行（成人 1，儿童 0）")).isEqualTo(1);
    }

    @Test
    @DisplayName("人数解析覆盖常见口语写法，且不会把天数/预算误当人数")
    void parsesPartySizeVariants() {
        assertThat(MessageParser.extractPartySize("我们3个人一起去")).isEqualTo(3);
        assertThat(MessageParser.extractPartySize("两个人")).isEqualTo(2);
        assertThat(MessageParser.extractPartySize("2大1小")).isEqualTo(3);
        assertThat(MessageParser.extractPartySize("一家三口")).isEqualTo(3);
        assertThat(MessageParser.extractPartySize("五个人")).isEqualTo(5);
        assertThat(MessageParser.extractPartySize("我独自出发")).isEqualTo(1);
        assertThat(MessageParser.extractPartySize("预算3000元，玩三天")).isEqualTo(-1);
        assertThat(MessageParser.extractPartySize("住 3 晚")).isEqualTo(-1);
    }

    @Test
    @DisplayName("人数收敛到 1..20：0 人按没人算，30 人截断到 20")
    void clampsPartySize() {
        assertThat(RequirementSlots.effectiveParty(0)).isEqualTo(1);
        assertThat(RequirementSlots.effectiveParty(null)).isEqualTo(1);
        assertThat(RequirementSlots.effectiveParty(30)).isEqualTo(20);
        assertThat(MessageParser.extractPartySize("30人")).isEqualTo(20);
    }

    @Test
    @DisplayName("追问后的极短回答：整句「上海」当城市；「三天」「两个人」不算城市")
    void parsesBareCityAnswer() {
        assertThat(RequirementSlots.fromMessage("上海").destination()).isEqualTo("上海");
        assertThat(RequirementSlots.fromMessage("北京").destination()).isEqualTo("北京");
        assertThat(RequirementSlots.fromMessage("三天").destination()).isNull();
        assertThat(RequirementSlots.fromMessage("两个人").destination()).isNull();
        assertThat(RequirementSlots.fromMessage("预算").destination()).isNull();
        assertThat(RequirementSlots.fromMessage("行程").destination()).isNull();
        // 拒绝回答同样是 2-4 个汉字，但不能被当成城市名
        assertThat(RequirementSlots.fromMessage("你决定吧").destination()).isNull();
        assertThat(RequirementSlots.fromMessage("随便").destination()).isNull();
    }

    @Test
    @DisplayName("合并：新消息优先，旧槽位只补空")
    void mergesNewOverOld() {
        RequirementSlots older = new RequirementSlots("上海", 3, 2, null, null);
        RequirementSlots newer = new RequirementSlots(null, 5, null, null, null);

        RequirementSlots merged = newer.fillMissingFrom(older);

        assertThat(merged.destination()).isEqualTo("上海");
        assertThat(merged.days()).isEqualTo(5);
        assertThat(merged.partySize()).isEqualTo(2);
    }

    @Test
    @DisplayName("兜底：缺的补默认值（目的地也兜底），已说的不动")
    void appliesDefaults() {
        LocalDate today = LocalDate.of(2026, 11, 18);
        RequirementSlots resolved = RequirementSlots.fromMessage("帮我规划杭州行程")
                .withDefaults(today);

        assertThat(resolved.destination()).isEqualTo("杭州");
        assertThat(resolved.days()).isEqualTo(RequirementSlots.DEFAULT_DAYS);
        assertThat(resolved.partySize()).isEqualTo(RequirementSlots.DEFAULT_PARTY_SIZE);
        assertThat(resolved.budget()).isEqualByComparingTo(RequirementSlots.DEFAULT_BUDGET);
        assertThat(resolved.startDate()).isEqualTo(today.plusDays(1));
    }

    @Test
    @DisplayName("拒绝回答的识别：随便/你决定…；真正的补充信息不算拒绝")
    void detectsDecline() {
        assertThat(RequirementSlots.isDecline("随便")).isTrue();
        assertThat(RequirementSlots.isDecline("你决定吧")).isTrue();
        assertThat(RequirementSlots.isDecline("都行")).isTrue();
        assertThat(RequirementSlots.isDecline("三天，两个人")).isFalse();
    }
}