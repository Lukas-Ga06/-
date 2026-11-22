package com.travel.agent.service;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 价格「参考价 + 来源 + 深链」的纯函数契约（ADR-006）。
 *
 * <p>这里不联网：所有断言都是 URL 形状、字段取值与降级行为。
 * 关键红线：没有实时源时不许出现任何「实时/有房/可订」的暗示，未实测的城市/缺日期的酒店
 * 宁可不给链接，也不能猜。
 */
class PriceReferenceServiceTest {

    private final PriceReferenceService service = new PriceReferenceService();

    private static final LocalDate START = LocalDate.of(2026, 11, 20);
    private static final LocalDate END = LocalDate.of(2026, 11, 21);

    private static ItineraryItem item(String type, String title, BigDecimal cost, Double lat, Double lng) {
        return ItineraryItem.builder()
                .itemType(type)
                .title(title)
                .cost(cost)
                .latitude(lat)
                .longitude(lng)
                .build();
    }

    private static List<String> providers(ItineraryDtos.PriceDTO price) {
        return price.getLinks().stream().map(ItineraryDtos.PriceLinkDTO::getProvider).collect(Collectors.toList());
    }

    @Test
    @DisplayName("cost 为 null / 0 / 负数时不给 price（不渲染「¥0 参考价」）")
    void noPriceWithoutPositiveCost() {
        assertThat(service.build(item(ItineraryItem.TYPE_HOTEL, "酒店", null, 31.2, 121.4),
                "上海", START, END)).isNull();
        assertThat(service.build(item(ItineraryItem.TYPE_ATTRACTION, "免费公园", BigDecimal.ZERO, 31.2, 121.4),
                "上海", START, END)).isNull();
        assertThat(service.build(item(ItineraryItem.TYPE_ATTRACTION, "异常数据", new BigDecimal("-1"), null, null),
                "上海", START, END)).isNull();
        assertThat(service.build(null, "上海", START, END)).isNull();
    }

    @Test
    @DisplayName("酒店命中北京/上海/杭州：携程 canonical 链接的 cityId 分别是 1/2/17")
    void hotelInMeasuredCitiesGetsCanonicalCtripLink() {
        ItineraryItem hotel = item(ItineraryItem.TYPE_HOTEL, "西湖国宾馆",
                new BigDecimal("520.00"), 30.25, 120.13);

        ItineraryDtos.PriceLinkDTO beijing = service.build(hotel, "北京", START, END).getLinks().get(0);
        assertThat(beijing.getProvider()).isEqualTo(PriceReferenceService.PROVIDER_CTRIP);
        assertThat(beijing.getUrl()).isEqualTo("https://hotels.ctrip.com/hotels/list"
                + "?flexType=1&cityId=1&provinceId=0&districtId=0&countryId=1"
                + "&checkin=2026-11-20&checkout=2026-11-21");

        assertThat(service.build(hotel, "上海", START, END).getLinks().get(0).getUrl())
                .contains("cityId=2&provinceId=0");
        assertThat(service.build(hotel, "杭州", START, END).getLinks().get(0).getUrl())
                .contains("cityId=17&provinceId=0");
    }

    @Test
    @DisplayName("城市名带「市」后缀也能命中实测表（北京=1）")
    void citySuffixStillMatches() {
        ItineraryItem hotel = item(ItineraryItem.TYPE_HOTEL, "酒店", new BigDecimal("300"), null, null);
        ItineraryDtos.PriceDTO price = service.build(hotel, "北京市", START, END);
        assertThat(price.getLinks().get(0).getUrl()).contains("cityId=1");
    }

    @Test
    @DisplayName("城市不在实测表内：不给携程链接（不猜 id，也不退化到会跳登录页的 city= 形式）")
    void unknownCityGetsNoCtripLink() {
        ItineraryItem hotel = item(ItineraryItem.TYPE_HOTEL, "春熙路酒店",
                new BigDecimal("480"), 30.6570, 104.0657);

        ItineraryDtos.PriceDTO price = service.build(hotel, "成都", START, END);
        assertThat(providers(price)).containsExactly(PriceReferenceService.PROVIDER_AMAP);
        assertThat(price.getLinks()).noneMatch(link -> link.getUrl().contains("ctrip"));
        assertThat(price.getLinks()).noneMatch(link -> link.getUrl().contains("city="));
    }

    @Test
    @DisplayName("缺 startDate/endDate 的酒店不给携程链接（checkin/checkout 不能编造）")
    void hotelWithoutTripDatesGetsNoCtripLink() {
        ItineraryItem hotel = item(ItineraryItem.TYPE_HOTEL, "酒店", new BigDecimal("520"), 31.2, 121.4);

        assertThat(providers(service.build(hotel, "上海", null, END)))
                .containsExactly(PriceReferenceService.PROVIDER_AMAP);
        assertThat(providers(service.build(hotel, "上海", START, null)))
                .containsExactly(PriceReferenceService.PROVIDER_AMAP);
    }

    @Test
    @DisplayName("非酒店条目永远不给携程链接，但有坐标仍给高德链接")
    void nonHotelNeverGetsCtripLink() {
        ItineraryItem attraction = item(ItineraryItem.TYPE_ATTRACTION, "西湖",
                new BigDecimal("60"), 30.2431, 120.1503);
        ItineraryDtos.PriceDTO price = service.build(attraction, "杭州", START, END);

        assertThat(providers(price)).containsExactly(PriceReferenceService.PROVIDER_AMAP);
        assertThat(price.getLinks()).allMatch(link -> link.getUrl().startsWith("https://"));
    }

    @Test
    @DisplayName("酒店 + 坐标：链接顺序为 [携程查价, 高德地图]，kind 分别为 price / map")
    void hotelLinksAreOrderedAndKinded() {
        ItineraryItem hotel = item(ItineraryItem.TYPE_HOTEL, "西湖国宾馆",
                new BigDecimal("520.00"), 30.25, 120.13);
        ItineraryDtos.PriceDTO price = service.build(hotel, "杭州", START, END);

        assertThat(providers(price)).containsExactly(
                PriceReferenceService.PROVIDER_CTRIP, PriceReferenceService.PROVIDER_AMAP);
        assertThat(price.getLinks().get(0).getKind()).isEqualTo(PriceReferenceService.LINK_KIND_PRICE);
        assertThat(price.getLinks().get(0).getLabel()).isEqualTo("去携程查价");
        assertThat(price.getLinks().get(1).getKind()).isEqualTo(PriceReferenceService.LINK_KIND_MAP);
        assertThat(price.getLinks().get(1).getLabel()).isEqualTo("在地图查看");
    }

    @Test
    @DisplayName("高德链接形状：position=经度,纬度、name 经 URL 编码、src/coordinate/callnative 固定")
    void amapLinkShapeAndEncoding() {
        ItineraryItem attraction = item(ItineraryItem.TYPE_ATTRACTION, "西湖 West Lake",
                new BigDecimal("60"), 30.25, 120.13);
        String url = service.build(attraction, "杭州", START, END).getLinks().get(0).getUrl();

        assertThat(url).isEqualTo("https://uri.amap.com/marker?position=120.13,30.25"
                + "&name=%E8%A5%BF%E6%B9%96%20West%20Lake"
                + "&src=travel-agent&coordinate=wgs84&callnative=0");
        // coordinate=wgs84 必须保留：去掉后高德会按 GCJ-02 解读，点位偏移数百米
        assertThat(url).contains("coordinate=wgs84");
        // 空格编码成 %20 而不是 +，保证链接在任何客户端里形态一致
        assertThat(url).doesNotContain("+");
    }

    @Test
    @DisplayName("坐标缺失或越界：不给地图链接；无任何可用链接时 links 是空数组而不是 null")
    void missingOrInvalidCoordinatesGetsEmptyLinkArray() {
        assertThat(service.build(item(ItineraryItem.TYPE_ATTRACTION, "只有纬度", new BigDecimal("10"), 31.2, null),
                "上海", START, END).getLinks()).isEmpty();
        assertThat(service.build(item(ItineraryItem.TYPE_ATTRACTION, "只有经度", new BigDecimal("10"), null, 121.4),
                "上海", START, END).getLinks()).isEmpty();
        assertThat(service.build(item(ItineraryItem.TYPE_ATTRACTION, "纬度越界", new BigDecimal("10"), 91.0, 121.4),
                "上海", START, END).getLinks()).isEmpty();
        assertThat(service.build(item(ItineraryItem.TYPE_ATTRACTION, "经度越界", new BigDecimal("10"), 31.2, 181.0),
                "上海", START, END).getLinks()).isEmpty();

        // 酒店 + 未知城市 + 无坐标：两个链接都生成不了 → 空数组，前端可无条件遍历
        ItineraryDtos.PriceDTO price = service.build(
                item(ItineraryItem.TYPE_HOTEL, "成都酒店", new BigDecimal("300"), null, null), "成都", START, END);
        assertThat(price).isNotNull();
        assertThat(price.getLinks()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("自报字段固定：kind=reference、source=local-estimate，金额保留目录价与两位小数")
    void priceFieldsAreHonestAndStable() {
        ItineraryDtos.PriceDTO price = service.build(
                item(ItineraryItem.TYPE_HOTEL, "酒店", new BigDecimal("520.00"), 31.2, 121.4),
                "上海", START, END);

        assertThat(price.getAmount()).isEqualByComparingTo("520.00");
        assertThat(price.getAmount().scale()).isEqualTo(2);
        assertThat(price.getCurrency()).isEqualTo("CNY");
        assertThat(price.getKind()).isEqualTo("reference");
        assertThat(price.getLabel()).isEqualTo("参考价");
        assertThat(price.getSource()).isEqualTo("local-estimate");
        assertThat(price.getSourceLabel()).isEqualTo("本地估算");
        assertThat(price.getDisclaimer())
                .startsWith("非实时报价")
                .contains("未接入房态/库存");
        // 任何字段都不许暗示「实时/有房/可订」为事实
        assertThat(price.getKind()).isNotEqualTo("live");
        assertThat(price.getSource()).isNotEqualTo("realtime");
        assertThat(price.getDisclaimer()).doesNotContain("有房");
    }

    @Test
    @DisplayName("Trip 重载取城市与起止日期：北京酒店带 cityId=1 与行程日期")
    void tripOverloadUsesTripCityAndDates() {
        Trip trip = Trip.builder().destination("北京").startDate(START).endDate(END).build();
        ItineraryDtos.PriceDTO price = service.build(
                item(ItineraryItem.TYPE_HOTEL, "酒店", new BigDecimal("520"), 39.9, 116.4), trip);

        assertThat(price.getLinks().get(0).getUrl())
                .contains("cityId=1")
                .contains("checkin=2026-11-20")
                .contains("checkout=2026-11-21");
    }

    @Test
    @DisplayName("状态页自报口径：provider=reference-estimate，hint 明确未接实时房态")
    void providerIsHonest() {
        assertThat(service.provider()).isEqualTo("reference-estimate");
        assertThat(service.hint()).contains("未接入实时房态").contains("参考");
    }
}