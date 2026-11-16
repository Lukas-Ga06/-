package com.travel.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 城市名匹配规则（CityNames）：包含 + 短边 ≥2、最长命中、编辑距离兜底、归一化。 */
class CityNamesTest {

    private static final List<String> CATALOG = List.of("上海", "北京", "杭州", "石家庄");

    @Test
    @DisplayName("单字任何时候都不命中任何城市（「阳」「州」「北」）")
    void singleCharacterNeverMatches() {
        assertThat(CityNames.matches("阳", "洛阳")).isFalse();
        assertThat(CityNames.matches("州", "杭州")).isFalse();
        assertThat(CityNames.matches("北", "北京")).isFalse();
        assertThat(CityNames.matches("杭州", "州")).isFalse();
        assertThat(CityNames.matches("洛阳", "阳")).isFalse();
        assertThat(CityNames.matches("杭州", "杭")).isFalse();
        assertThat(CityNames.longestMatch("阳", CATALOG)).isNull();
        assertThat(CityNames.nearest("州", CATALOG, 1)).isEmpty();
    }

    @Test
    @DisplayName("完全相等与常见后缀差异视为同一城市")
    void matchesSameCity() {
        assertThat(CityNames.matches("杭州", "杭州")).isTrue();
        assertThat(CityNames.matches("杭州", "杭州市")).isTrue();
        assertThat(CityNames.matches("杭州市", "杭州")).isTrue();
        // 包含关系 + 短边 ≥ 2
        assertThat(CityNames.matches("上海", "上海市浦东新区")).isTrue();
        assertThat(CityNames.matches("上海市浦东新区", "上海")).isTrue();
    }

    @Test
    @DisplayName("无关城市与空值不匹配")
    void unrelatedOrEmptyDoNotMatch() {
        assertThat(CityNames.matches("上海", "北京")).isFalse();
        assertThat(CityNames.matches("乌鲁木齐", "齐齐哈尔")).isFalse();
        assertThat(CityNames.matches(null, "上海")).isFalse();
        assertThat(CityNames.matches("上海", null)).isFalse();
        assertThat(CityNames.matches("   ", "上海")).isFalse();
        assertThat(CityNames.matches("上海", "")).isFalse();
    }

    @Test
    @DisplayName("normalize：去首尾空白与城市级后缀，不剥「区/县/新区」")
    void normalizeStripsOnlyCityLevelSuffixes() {
        assertThat(CityNames.normalize(null)).isEmpty();
        assertThat(CityNames.normalize("   ")).isEmpty();
        assertThat(CityNames.normalize(" 杭州市 ")).isEqualTo("杭州");
        assertThat(CityNames.normalize("上海地区")).isEqualTo("上海");
        assertThat(CityNames.normalize("香港特别行政区")).isEqualTo("香港");
        assertThat(CityNames.normalize("延边朝鲜族自治州")).isEqualTo("延边朝鲜族");
        // 区/县不是可剥后缀：浦东新区不能被剥成浦东
        assertThat(CityNames.normalize("浦东新区")).isEqualTo("浦东新区");
        assertThat(CityNames.normalize("杭州")).isEqualTo("杭州");
    }

    @Test
    @DisplayName("longestMatch：文本里取最长命中，不按集合顺序 first-match")
    void longestMatchPicksLongestName() {
        assertThat(CityNames.longestMatch("杭州西湖", List.of("杭州", "上海"))).isEqualTo("杭州");
        assertThat(CityNames.longestMatch("我想去杭州市", List.of("上海", "北京", "杭州", "石家庄")))
                .isEqualTo("杭州");
        assertThat(CityNames.longestMatch("我想去石家庄", List.of("上海", "北京", "杭州", "石家庄")))
                .isEqualTo("石家庄");
        // 名字更长者优先
        assertThat(CityNames.longestMatch("杭州西湖", List.of("杭州", "杭州西湖"))).isEqualTo("杭州西湖");
        // 无命中
        assertThat(CityNames.longestMatch("我想去火星", CATALOG)).isNull();
        assertThat(CityNames.longestMatch(null, CATALOG)).isNull();
        assertThat(CityNames.longestMatch("杭州", List.of())).isNull();
    }

    @Test
    @DisplayName("longestMatch：等长多解不猜，返回 null")
    void longestMatchAmbiguousTieReturnsNull() {
        assertThat(CityNames.longestMatch("上海北京", List.of("上海", "北京"))).isNull();
    }

    @Test
    @DisplayName("nearest：编辑距离 1 的 typo 能纠正（杭洲 → 杭州）")
    void nearestFixesSingleTypo() {
        Optional<String> matched = CityNames.nearest("杭洲", CATALOG, 1);
        assertThat(matched).contains("杭州");
    }

    @Test
    @DisplayName("nearest：距离 2 不认（广洲 与 杭州 距离 2，广州 不在目录）")
    void nearestRejectsLargerDistance() {
        assertThat(CityNames.nearest("广洲", CATALOG, 1)).isEmpty();
        // 距离 0 的完全相等也认（唯一解）
        assertThat(CityNames.nearest("杭州", CATALOG, 1)).contains("杭州");
        assertThat(CityNames.nearest("杭洲", CATALOG, 0)).isEmpty();
    }

    @Test
    @DisplayName("nearest：等距多解返回空，不随便挑一个")
    void nearestAmbiguousTieReturnsEmpty() {
        // 「广洲」与「广州」「广元」都只差第 2 位，但指向两个不同城市 → 不猜
        assertThat(CityNames.nearest("广洲", List.of("广州", "广元"), 1)).isEmpty();
    }

    @Test
    @DisplayName("nearest：首位笔误不兜底（广州/苏州/常州/南京 不能被认成目录城市）")
    void nearestRejectsFirstCharacterTypo() {
        // 首字替换无法与「目录外的真实城市」区分：广州/苏州/常州 → 杭州，南京/东京 → 北京
        assertThat(CityNames.nearest("广州", Set.of("杭州", "北京", "上海", "石家庄"), 1)).isEmpty();
        assertThat(CityNames.nearest("苏州", CATALOG, 1)).isEmpty();
        assertThat(CityNames.nearest("常州", CATALOG, 1)).isEmpty();
        assertThat(CityNames.nearest("南京", CATALOG, 1)).isEmpty();
        assertThat(CityNames.nearest("东京", CATALOG, 1)).isEmpty();
        // 非首位笔误仍生效（石家庒 第 3 位不同）
        assertThat(CityNames.nearest("石家庒", CATALOG, 1)).contains("石家庄");
    }

    @Test
    @DisplayName("nearest：null / 单字输入安全")
    void nearestHandlesNullAndSingleCharacter() {
        assertThat(CityNames.nearest(null, CATALOG, 1)).isEmpty();
        assertThat(CityNames.nearest("杭", CATALOG, 1)).isEmpty();
        assertThat(CityNames.nearest("杭洲", null, 1)).isEmpty();
    }
}