package com.travel.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Hotel;
import com.travel.agent.model.Restaurant;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 联网城市目录的「提示词 + JSON → 领域模型」解析器。
 *
 * <p>与 {@link OnlineCityService} 的分工：本类只负责把模型输出解析成 {@link CityCatalog}
 * （含默认值兜底与脏数据过滤），不发起网络调用、不写缓存、不注册目录。
 *
 * <p>两条路径的 JSON 形状不同：
 * <ul>
 *   <li>紧凑路径：模型只给名称/属性，坐标由高德批量补齐（{@link #readCompact}）；</li>
 *   <li>兜底路径：模型一次给全量字段含坐标（{@link #readFull}）。</li>
 * </ul>
 */
@Component
public class OnlineCityCatalogReader {

    private final ObjectMapper objectMapper;

    public OnlineCityCatalogReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 提示词 ====================

    /** 快速路径提示词：只要名称与关键属性，坐标交给高德（紧凑 JSON，约 2~3 秒） */
    public String compactPrompt(String name) {
        return """
                为城市「%s」生成旅行数据，全部使用真实存在的知名地点名称。仅输出 JSON：
                {"city":"%s","attractions":[{"n":"景点名","v":"o","t":["历史","地标"],"p":0},...6个],"hotels":[{"n":"酒店名","m":1,"p":350},...3个],"restaurants":[{"n":"餐厅名","c":"菜系","p":60},...4个]}
                说明：v 取 o(室外)或 i(室内)；t 为 2 个中文标签；p 为大致价格元(免费填0)；m=1 表示近地铁；hotel 用经济型/中端连锁，restaurant 用有本地特色的老字号或名店。
                """.formatted(name, name);
    }

    /** 兜底路径提示词：一次生成含坐标的完整目录 */
    public String fullPrompt(String name) {
        return """
                请为城市「%s」生成旅行目录，POI 必须使用真实存在的知名地点与大致经纬度（位于该城市的真实地理范围内）。
                要求：
                1. attractions：6~8 个真实知名景点；venue 取 indoor（博物馆/展馆等）或 outdoor；tags 为 2~4 个中文标签（如 文化、历史、亲子、自然、地标）；ticket 为大致门票价格（元，免费填 0）；durationHours 为建议游览时长；rating 在 4.0~4.9；description 为一句话介绍。
                2. hotels：3 家真实酒店（以经济型/中端连锁为主）；nearSubway 表示是否近地铁；pricePerNight 为每晚估算价（元）；rating 在 4.0~4.9；lat/lng 为大致坐标。
                3. restaurants：4 家有本地特色的真实餐厅；cuisine 为菜系；avgCost 为人均消费（元）；rating 在 4.0~4.9；description 一句话。
                仅输出 JSON：
                {"name":"%s","attractions":[{"id":"a1","name":"...","venue":"outdoor","tags":["文化"],"lat":0.0,"lng":0.0,"ticket":0,"durationHours":2,"rating":4.5,"description":"..."}],"hotels":[{"id":"h1","name":"...","nearSubway":true,"pricePerNight":350,"rating":4.6,"lat":0.0,"lng":0.0,"description":"..."}],"restaurants":[{"id":"r1","name":"...","cuisine":"本地菜","avgCost":60,"rating":4.5,"lat":0.0,"lng":0.0,"description":"..."}]}
                """.formatted(name, name);
    }

    // ==================== JSON → 模型 ====================

    /**
     * 解析快速路径的紧凑 JSON，并用高德返回的坐标组装目录；定位失败的 POI 直接丢弃。
     *
     * @param cityKey 城市名（用于生成稳定的 POI id 前缀）
     * @param coords  高德批量定位结果（POI 名 → [lat, lng]）
     */
    public CityCatalog readCompact(String raw, String cityKey, Map<String, double[]> coords)
            throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(stripFences(raw));
        String cityName = node.path("city").asText(cityKey);

        List<Attraction> attractions = new ArrayList<>();
        int[] ai = {0};
        node.path("attractions").forEach(n -> {
            String poiName = n.path("n").asText("").trim();
            double[] loc = resolveCoords(coords, poiName);
            if (poiName.isEmpty() || loc == null || attractions.size() >= 8) return;
            String venue = "i".equalsIgnoreCase(n.path("v").asText("o")) ? "indoor" : "outdoor";
            List<String> tags = stringList(n.path("t"));
            tags = tags.isEmpty() ? List.of(venue.equals("indoor") ? "室内" : "景点") : tags;
            double ticket = Math.max(n.path("p").asDouble(0), 0);
            attractions.add(new Attraction("ON-" + cityKey + "-A" + (++ai[0]), poiName, venue, tags,
                    loc[0], loc[1], ticket, 2, rating(ai[0]), descFor(venue, ticket)));
        });

        List<Hotel> hotels = new ArrayList<>();
        int[] hi = {0};
        node.path("hotels").forEach(n -> {
            String poiName = n.path("n").asText("").trim();
            double[] loc = resolveCoords(coords, poiName);
            if (poiName.isEmpty() || loc == null || hotels.size() >= 4) return;
            double price = Math.min(Math.max(n.path("p").asDouble(320), 120), 2000);
            boolean nearSubway = n.path("m").asBoolean(false) || n.path("m").asInt(0) == 1;
            hotels.add(new Hotel("ON-" + cityKey + "-H" + (++hi[0]), poiName, nearSubway,
                    price, rating(hi[0]), loc[0], loc[1],
                    // 文案沿用原语义：只有布尔 true 才追加地铁提示（数字 1 的提示留给展示层判断）
                    (n.path("m").asBoolean(false) ? "临近地铁，出行便利。" : "") + "实际价格以预订平台为准。"));
        });

        List<Restaurant> restaurants = new ArrayList<>();
        int[] ri = {0};
        node.path("restaurants").forEach(n -> {
            String poiName = n.path("n").asText("").trim();
            double[] loc = resolveCoords(coords, poiName);
            if (poiName.isEmpty() || loc == null || restaurants.size() >= 6) return;
            double cost = Math.min(Math.max(n.path("p").asDouble(60), 20), 500);
            String cuisine = n.path("c").asText("").trim();
            if (cuisine.isEmpty()) cuisine = "本地美食";
            restaurants.add(new Restaurant("ON-" + cityKey + "-R" + (++ri[0]), poiName, cuisine,
                    cost, rating(ri[0]), loc[0], loc[1],
                    cuisine + "，人均约 ¥" + (int) cost + "，口味以现场体验为准。"));
        });
        return new CityCatalog(cityName, attractions, hotels, restaurants);
    }

    /**
     * 从快速路径的紧凑 JSON 中取出全部 POI 名称（景点 → 酒店 → 餐厅，保持模型输出顺序），
     * 供 {@link CityGeoLocator#locatePois(List, String)} 批量定位。
     */
    public List<String> compactPoiNames(String raw) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(stripFences(raw));
        List<String> names = new ArrayList<>();
        for (String field : List.of("attractions", "hotels", "restaurants")) {
            node.path(field).forEach(n -> names.add(n.path("n").asText("")));
        }
        names.removeIf(String::isBlank);
        return names;
    }

    /** 解析兜底路径的完整 JSON（含坐标），脏数据按默认值过滤 */
    public CityCatalog readFull(String raw, String cityKey) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(stripFences(raw));
        String cityName = node.path("name").asText(cityKey);
        return new CityCatalog(cityName,
                parseAttractions(node.path("attractions"), cityKey),
                parseHotels(node.path("hotels"), cityKey),
                parseRestaurants(node.path("restaurants"), cityKey));
    }

    /** 坐标解析：先精确名，失败后尝试去掉分店括号再查一次 */
    private double[] resolveCoords(Map<String, double[]> coords, String poiName) {
        double[] loc = coords.get(poiName);
        if (loc != null) {
            return loc;
        }
        String shortName = poiName.replaceAll("[（(].*?[)）]", "").trim();
        return shortName.isEmpty() ? null : coords.get(shortName);
    }

    private List<Attraction> parseAttractions(JsonNode arr, String key) {
        List<Attraction> list = new ArrayList<>();
        if (arr == null || !arr.isArray()) return list;
        int i = 0;
        for (JsonNode n : arr) {
            String name = n.path("name").asText("").trim();
            double lat = n.path("lat").asDouble(0);
            double lng = n.path("lng").asDouble(0);
            if (name.isEmpty() || Math.abs(lat) < 1 || Math.abs(lng) < 1) continue;
            String venue = "indoor".equalsIgnoreCase(n.path("venue").asText("")) ? "indoor" : "outdoor";
            List<String> tags = stringList(n.path("tags"));
            tags = tags.isEmpty() ? List.of(venue.equals("indoor") ? "室内" : "景点") : tags;
            double ticket = Math.max(n.path("ticket").asDouble(0), 0);
            double duration = n.path("durationHours").asDouble(2);
            if (duration <= 0 || duration > 8) duration = 2;
            double rating = normalizeRating(n.path("rating").asDouble(4.2));
            String desc = n.path("description").asText("").trim();
            if (desc.isEmpty()) desc = "热门景点，门票与开放时间以现场为准。";
            list.add(new Attraction("ON-" + key + "-A" + (++i), name, venue, tags, lat, lng,
                    ticket, duration, rating, desc));
            if (list.size() >= 8) break;
        }
        return list;
    }

    private List<Hotel> parseHotels(JsonNode arr, String key) {
        List<Hotel> list = new ArrayList<>();
        if (arr == null || !arr.isArray()) return list;
        int i = 0;
        for (JsonNode n : arr) {
            String name = n.path("name").asText("").trim();
            double lat = n.path("lat").asDouble(0);
            double lng = n.path("lng").asDouble(0);
            if (name.isEmpty() || Math.abs(lat) < 1 || Math.abs(lng) < 1) continue;
            double price = n.path("pricePerNight").asDouble(320);
            price = Math.min(Math.max(price, 120), 2000);
            double rating = normalizeRating(n.path("rating").asDouble(4.4));
            String desc = n.path("description").asText("").trim();
            if (desc.isEmpty()) desc = "位置便利的酒店，实际价格以预订平台为准。";
            list.add(new Hotel("ON-" + key + "-H" + (++i), name,
                    n.path("nearSubway").asBoolean(false), price, rating, lat, lng, desc));
            if (list.size() >= 4) break;
        }
        return list;
    }

    private List<Restaurant> parseRestaurants(JsonNode arr, String key) {
        List<Restaurant> list = new ArrayList<>();
        if (arr == null || !arr.isArray()) return list;
        int i = 0;
        for (JsonNode n : arr) {
            String name = n.path("name").asText("").trim();
            double lat = n.path("lat").asDouble(0);
            double lng = n.path("lng").asDouble(0);
            if (name.isEmpty() || Math.abs(lat) < 1 || Math.abs(lng) < 1) continue;
            double cost = n.path("avgCost").asDouble(60);
            cost = Math.min(Math.max(cost, 20), 500);
            double rating = normalizeRating(n.path("rating").asDouble(4.3));
            String cuisine = n.path("cuisine").asText("").trim();
            if (cuisine.isEmpty()) cuisine = "本地美食";
            String desc = n.path("description").asText("").trim();
            if (desc.isEmpty()) desc = "本地特色餐厅。";
            list.add(new Restaurant("ON-" + key + "-R" + (++i), name, cuisine, cost, rating, lat, lng, desc));
            if (list.size() >= 6) break;
        }
        return list;
    }

    /** 从 JSON 数组取非空字符串（保持原插入顺序；数量由上游模型产出约束，这里不截断） */
    private List<String> stringList(JsonNode arr) {
        List<String> values = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return values;
        }
        for (JsonNode item : arr) {
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return values;
    }

    private double normalizeRating(double rating) {
        if (rating <= 0 || rating > 5) {
            return 4.2;
        }
        return Math.round(rating * 10) / 10.0;
    }

    /** 快速路径没有逐项评分：用稳定的小数抖动给出 4.3~4.7 的展示值（仅用于展示，不参与决策） */
    private double rating(int index) {
        return 4.3 + (index * 7 % 5) / 10.0;
    }

    private String descFor(String venue, double ticket) {
        String base = "indoor".equals(venue) ? "室内场馆，适合雨天与亲子安排。" : "户外景点，出行前请留意天气。";
        return base + (ticket > 0 ? "门票约 ¥" + (int) ticket + "，以现场为准。" : "免费开放，以现场公告为准。");
    }

    private String stripFences(String raw) {
        return raw.replace("```json", "").replace("```", "").trim();
    }
}