package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.model.CityCatalog;
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

    // ==================== CityAtlas 回归：单字不得命中任何城市 ====================

    @Test
    @DisplayName("足迹地图 CityAtlas：单字不再误配（「阳」不再命中「洛阳」）")
    void cityAtlasSingleCharacterNeverResolves() {
        CityAtlas atlas = new CityAtlas();
        assertThat(atlas.resolve("阳")).isNull();
        assertThat(atlas.resolve("州")).isNull();
        assertThat(atlas.resolve("北")).isNull();
        // 反向：图集条目含「洛阳」，单字查询也不能命中
        assertThat(atlas.resolve("洛")).isNull();
    }

    @Test
    @DisplayName("足迹地图 CityAtlas：正常城市与后缀差异仍能解析")
    void cityAtlasResolvesCities() {
        CityAtlas atlas = new CityAtlas();
        assertThat(atlas.resolve("杭州")).containsExactly(30.2741, 120.1551);
        assertThat(atlas.resolve("杭州市")).containsExactly(30.2741, 120.1551);
        assertThat(atlas.resolve(" 杭州 ")).containsExactly(30.2741, 120.1551);
        // 模糊：取最长命中（「杭州西湖」→ 杭州）
        assertThat(atlas.resolve("杭州西湖")).containsExactly(30.2741, 120.1551);
        assertThat(atlas.resolve("杭州")).isEqualTo(atlas.resolve("杭州西湖"));
        assertThat(atlas.resolve("洛阳")).isNotNull();
        assertThat(atlas.resolve(null)).isNull();
        assertThat(atlas.resolve("   ")).isNull();
    }

    // ==================== TravelCatalogService：最长命中 + typo 兜底 ====================

    @Test
    @DisplayName("目录匹配：按最长命中，不再依赖目录数组顺序")
    void catalogMatchUsesLongestMatch() {
        TravelCatalogService catalog = catalog();
        assertThat(catalog.matchCity("我想去杭州")).isNotNull()
                .extracting(CityCatalog::name).isEqualTo("杭州");
        assertThat(catalog.matchCity("我想去石家庄")).isNotNull()
                .extracting(CityCatalog::name).isEqualTo("石家庄");
        assertThat(catalog.matchCity("上海")).isNotNull()
                .extracting(CityCatalog::name).isEqualTo("上海");
        assertThat(catalog.matchCity(null)).isNull();
        assertThat(catalog.matchCity("  ")).isNull();
        assertThat(catalog.matchCity("我想去火星")).isNull();
    }

    @Test
    @DisplayName("目录匹配：typo 兜底只认非首位单字笔误（杭洲/石家庒 → 命中）")
    void catalogMatchFallsBackToTypo() {
        TravelCatalogService catalog = catalog();
        assertThat(catalog.matchCity("我想去杭洲")).isNotNull()
                .extracting(CityCatalog::name).isEqualTo("杭州");
        assertThat(catalog.matchCity("杭洲")).isNotNull()
                .extracting(CityCatalog::name).isEqualTo("杭州");
        // 第 3 位笔误仍生效
        assertThat(catalog.matchCity("我想去石家庒")).isNotNull()
                .extracting(CityCatalog::name).isEqualTo("石家庄");
        // 「广洲」到目录内任何城市都不止 1 处非首位差异，不能硬猜
        assertThat(catalog.matchCity("广洲")).isNull();
    }

    @Test
    @DisplayName("目录匹配：首位笔误不兜底，目录外真实城市继续走「不支持该目的地」")
    void catalogMatchKeepsRealCitiesOutsideCatalog() {
        TravelCatalogService catalog = catalog();
        // 广州/苏州/常州 vs 杭州、南京/东京 vs 北京 都只差首字，但它们是目录外真实城市
        assertThat(catalog.matchCity("我想去广州")).isNull();
        assertThat(catalog.matchCity("我想去苏州")).isNull();
        assertThat(catalog.matchCity("我想去南京")).isNull();
        assertThat(catalog.matchCity("广州")).isNull();
        assertThat(catalog.matchCity("南京")).isNull();
    }

    @Test
    @DisplayName("目录匹配：先剥出发地，不能把「北京出发去西安」匹配成北京")
    void catalogMatchIgnoresOriginCity() {
        TravelCatalogService catalog = catalog();
        assertThat(catalog.matchCity("从南京出发去杭州")).isNotNull()
                .extracting(CityCatalog::name).isEqualTo("杭州");
        assertThat(catalog.matchCity("北京出发去上海")).isNotNull()
                .extracting(CityCatalog::name).isEqualTo("上海");
        // 出发地在目录内、目的地在目录外：不能退回出发地
        assertThat(catalog.matchCity("北京出发去西安")).isNull();
        assertThat(catalog.matchCity("从上海出发去南京")).isNull();
    }

    @Test
    @DisplayName("目录注册：脏城市名不进入目录，也不出现在匹配候选里")
    void registerRejectsDirtyCityNames() {
        TravelCatalogService catalog = catalog();
        catalog.register(new CityCatalog("成都吃", java.util.List.of(), java.util.List.of(), java.util.List.of()));
        catalog.register(new CityCatalog("州镇江的", java.util.List.of(), java.util.List.of(), java.util.List.of()));

        assertThat(catalog.cities()).extracting(CityCatalog::name)
                .doesNotContain("成都吃", "州镇江的");
        assertThat(catalog.matchCity("成都吃四天")).isNull();
    }

    private TravelCatalogService catalog() {
        TravelCatalogService catalog = new TravelCatalogService(new ObjectMapper(), new RouteService());
        catalog.load();
        return catalog;
    }
}
