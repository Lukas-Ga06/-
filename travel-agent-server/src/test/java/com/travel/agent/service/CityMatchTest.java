package com.travel.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 城市名模糊匹配：既要有用（"石家庄市"↔"石家庄"），又不能串城市（"阳"↔"洛阳"） */
class CityMatchTest {

    @Test
    @DisplayName("完全一致与常见后缀差异视为同一城市")
    void matchesSameCity() {
        assertThat(OnlineCityService.matchesCity("洛阳", "洛阳")).isTrue();
        assertThat(OnlineCityService.matchesCity("石家庄", "家庄")).isTrue();
        assertThat(OnlineCityService.matchesCity("呼和浩特", "呼和浩特")).isTrue();
    }

    @Test
    @DisplayName("单字查询不再串城市（此前「阳」会命中「洛阳」）")
    void singleCharacterQueryNeverMatches() {
        assertThat(OnlineCityService.matchesCity("洛阳", "阳")).isFalse();
        assertThat(OnlineCityService.matchesCity("杭州", "州")).isFalse();
        assertThat(OnlineCityService.matchesCity("北京", "北")).isFalse();
        // 反向同样成立：缓存里存的是单字城市名（异常数据）时也不该命中
        assertThat(OnlineCityService.matchesCity("阳", "洛阳")).isFalse();
    }

    @Test
    @DisplayName("无关城市不匹配")
    void unrelatedCitiesDoNotMatch() {
        assertThat(OnlineCityService.matchesCity("上海", "北京")).isFalse();
        assertThat(OnlineCityService.matchesCity("乌鲁木齐", "齐齐哈尔")).isFalse();
    }

    @Test
    @DisplayName("null 安全")
    void handlesNull() {
        assertThat(OnlineCityService.matchesCity(null, "上海")).isFalse();
        assertThat(OnlineCityService.matchesCity("上海", null)).isFalse();
    }
}
