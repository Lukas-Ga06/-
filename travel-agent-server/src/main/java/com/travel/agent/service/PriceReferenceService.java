package com.travel.agent.service;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.util.CityNames;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 价格的「自报家门」（ADR-006）：把行程项上的裸数字包装成「参考价 + 来源 + 深链」。
 *
 * <p><b>能力边界（不许越过）</b>：当前没有任何实时房价 / 库存 / 可订状态数据源，
 * 因此 {@code kind} 恒为 {@code reference}、{@code source} 恒为 {@code local-estimate}，
 * {@code disclaimer} 明确写「非实时报价」。这里<b>不做也不假装</b>实时报价：
 * 不联网、不读缓存、不猜测房态，只把已知的本地估算价补齐来源与可跳转的查价入口。
 *
 * <p>纯函数式：无网络、无外部依赖、无状态，可被单测直接 new 出来断言 URL 形状。
 *
 * <p>深链取舍（均为 2026-11-19 联调实测记录，不是文档照抄）：
 * <ul>
 *   <li><b>携程（仅酒店）</b>：canonical 列表页 {@code /hotels/list} 带
 *       {@code flexType/cityId/provinceId/districtId/countryId/checkin/checkout}；
 *       城市 id 只认实测表（北京=1、上海=2、杭州=17），未命中就**不生成**链接——
 *       猜 id 会把用户带到别的城市，退化成 {@code city=<名字>} 形式则会 302 到登录页。
 *       缺行程日期同样不生成：checkin/checkout 无法编造。</li>
 *   <li><b>高德</b>：URI API 的 marker 形式，所有有坐标的条目（酒店/餐饮/景点）都给；
 *       {@code coordinate=wgs84} <b>必须保留</b>——业务坐标是 WGS-84，高德按该参数自己纠偏到 GCJ-02，
 *       去掉它会让点位整体偏移几百米。</li>
 *   <li><b>大众点评：禁止使用</b>（实测搜索页 302 到 account.dianping.com/pclogin
 *       或 verify.meituan.com，不能作为深链）。</li>
 * </ul>
 */
@Service
public class PriceReferenceService {

    /** 价格能力自报值：与 routeProvider 同一口径——没有实时源就不许宣称实时 */
    public static final String PROVIDER_REFERENCE_ESTIMATE = "reference-estimate";
    public static final String PRICE_HINT =
            "价格为本地参考估算（未接入实时房态/库存）：实际价格与可订状态以 OTA 页面为准";

    public static final String CURRENCY_CNY = "CNY";
    public static final String KIND_REFERENCE = "reference";
    public static final String SOURCE_LOCAL_ESTIMATE = "local-estimate";
    public static final String LABEL_REFERENCE = "参考价";
    public static final String SOURCE_LABEL = "本地估算";
    public static final String DISCLAIMER =
            "非实时报价：未接入房态/库存，实际价格与可订状态以 OTA 页面为准";

    public static final String PROVIDER_CTRIP = "ctrip";
    public static final String PROVIDER_AMAP = "amap";
    public static final String LINK_KIND_PRICE = "price";
    public static final String LINK_KIND_MAP = "map";

    private static final String CTRIP_LIST_BASE = "https://hotels.ctrip.com/hotels/list";
    private static final String AMAP_MARKER_BASE = "https://uri.amap.com/marker";
    /** 高德 URI 的 src 标识（渠道归因，固定值） */
    private static final String AMAP_SRC = "travel-agent";

    /**
     * 携程城市 id 表（已实测：北京=1、上海=2、杭州=17，2026-11-19 联调记录）。
     *
     * <p>刻意只收录实测过的城市：表外城市宁可不给链接，也不能猜 id——携程的 cityId 不是 adcode，
     * 猜错会跳到别的城市；用 {@code city=<名字>} 的旧形式则会把未登录用户弹到登录页。
     * 用 LinkedHashMap 保证遍历顺序稳定（多城市目的地文本里先命中先返回，结果可复现）。
     */
    private static final Map<String, Integer> CTRIP_CITY_IDS;

    static {
        Map<String, Integer> ids = new LinkedHashMap<>();
        ids.put("北京", 1);
        ids.put("上海", 2);
        ids.put("杭州", 17);
        CTRIP_CITY_IDS = Collections.unmodifiableMap(ids);
    }

    /**
     * 行程项 → 参考价。{@code cost} 为 null 或 0（含异常负数）时返回 null，表示「没有可展示的价格」。
     *
     * @param item 行程项（价格、类型、坐标、标题来源）
     * @param trip 所属行程（城市与日期来源，可为 null：缺行程上下文时只给地图链接）
     */
    public ItineraryDtos.PriceDTO build(ItineraryItem item, Trip trip) {
        if (item == null) {
            return null;
        }
        LocalDate start = trip == null ? null : trip.getStartDate();
        LocalDate end = trip == null ? null : trip.getEndDate();
        String destination = trip == null ? null : trip.getDestination();
        return build(item, destination, start, end);
    }

    /**
     * 纯函数入口（单测直接调用，不依赖 Trip 实体）：价格 + 深链全部由入参推导。
     *
     * @param destination 目的地城市名（用于携程 cityId 查表，大小写/「市」后缀由 {@link CityNames} 归一）
     */
    public ItineraryDtos.PriceDTO build(ItineraryItem item, String destination,
                                        LocalDate startDate, LocalDate endDate) {
        BigDecimal amount = item == null ? null : item.getCost();
        // 0 / null 视为「没有价格」：前端不渲染价格区块，也不需要「¥0 参考价」这种误导
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        List<ItineraryDtos.PriceLinkDTO> links = new ArrayList<>();
        String ctrip = ctripUrl(item, destination, startDate, endDate);
        if (ctrip != null) {
            links.add(ItineraryDtos.PriceLinkDTO.builder()
                    .provider(PROVIDER_CTRIP).label("去携程查价").url(ctrip).kind(LINK_KIND_PRICE).build());
        }
        String amap = amapUrl(item);
        if (amap != null) {
            links.add(ItineraryDtos.PriceLinkDTO.builder()
                    .provider(PROVIDER_AMAP).label("在地图查看").url(amap).kind(LINK_KIND_MAP).build());
        }
        return ItineraryDtos.PriceDTO.builder()
                .amount(amount)
                .currency(CURRENCY_CNY)
                .kind(KIND_REFERENCE)
                .label(LABEL_REFERENCE)
                .source(SOURCE_LOCAL_ESTIMATE)
                .sourceLabel(SOURCE_LABEL)
                .disclaimer(DISCLAIMER)
                // 无可用链接时给空数组（不是 null）：前端可无条件遍历
                .links(links)
                .build();
    }

    /**
     * 携程查价深链；不满足条件返回 null（调用方据此不生成链接）。
     *
     * <p>条件：仅酒店 + 城市在实测表内 + 行程起止日期齐全。三个条件缺一不可，
     * 因为任何一项不满足都只能靠猜，而猜出来的链接等于把用户带去错误页面。
     */
    static String ctripUrl(ItineraryItem item, String destination, LocalDate startDate, LocalDate endDate) {
        if (item == null || !ItineraryItem.TYPE_HOTEL.equals(item.getItemType())) {
            return null;
        }
        if (startDate == null || endDate == null) {
            return null;
        }
        Integer cityId = ctripCityId(destination);
        if (cityId == null) {
            return null;
        }
        return CTRIP_LIST_BASE + "?flexType=1&cityId=" + cityId
                + "&provinceId=0&districtId=0&countryId=1"
                + "&checkin=" + startDate + "&checkout=" + endDate;
    }

    /** 城市名 → 携程 cityId；表外/空值返回 null（绝不猜） */
    static Integer ctripCityId(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        for (Map.Entry<String, Integer> entry : CTRIP_CITY_IDS.entrySet()) {
            if (CityNames.matches(destination, entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 高德地图点位深链；坐标缺失/越界返回 null。
     *
     * <p>{@code coordinate=wgs84} 是契约的一部分：业务坐标来自 OSM/高德 WGS-84 数据，
     * 移除该参数会让高德按 GCJ-02 解释，点位偏移数百米。
     */
    static String amapUrl(ItineraryItem item) {
        if (item == null) {
            return null;
        }
        Double lat = item.getLatitude();
        Double lng = item.getLongitude();
        if (!validCoordinate(lat, lng)) {
            return null;
        }
        return AMAP_MARKER_BASE + "?position=" + plain(lng) + "," + plain(lat)
                + "&name=" + encode(item.getTitle())
                + "&src=" + AMAP_SRC + "&coordinate=wgs84&callnative=0";
    }

    /** 坐标合法性：纬度 ±90、经度 ±180；缺一不可（高德不接受单坐标） */
    private static boolean validCoordinate(Double lat, Double lng) {
        return lat != null && lng != null
                && Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90d && lat <= 90d
                && lng >= -180d && lng <= 180d;
    }

    /** 坐标文本：去掉二进制浮点尾巴与科学计数法（120.13 而不是 120.13000000000001） */
    private static String plain(Double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * 查询参数编码：{@link URLEncoder} 的 {@code +} 在 URI 查询串里语义不统一，统一换成 {@code %20}，
     * 让链接在任何客户端/剪贴板里都保持同一形态。
     */
    private static String encode(String value) {
        String raw = value == null ? "" : value;
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 价格能力自报：当前只有本地参考估算（无实时源，取值域不新增「实时」语义） */
    public String provider() {
        return PROVIDER_REFERENCE_ESTIMATE;
    }

    /** 状态页提示文案：必须与 provider 口径一致，不许暗示有房/可订 */
    public String hint() {
        return PRICE_HINT;
    }
}