package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Hotel;
import com.travel.agent.model.Restaurant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 联网城市数据引擎：内置目录之外的城市，按需在线生成旅行目录。
 *
 * 快速路径（配置高德 Key 后启用）：
 *   1) 大模型精简生成 POI 名称与属性（紧凑 JSON，约 2~3 秒）；
 *   2) 高德地理编码 API（国内可达）批量解析真实坐标（约 1 秒）。
 * 兜底路径（无 Key）：大模型一次性生成含坐标的完整目录 + OSM Nominatim 并行定位。
 *
 * 两级缓存：内存 24 小时 + 本地文件 7 天。
 * 生产升级路径：接入高德 POI 搜索/路径规划获取实时数据。
 */
@Service
@Slf4j
public class OnlineCityService {

    private static final Duration TTL = Duration.ofHours(24);
    private static final Set<String> GENERIC_WORDS = Set.of("城市", "哪里", "那里", "这里", "地方");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final TravelCatalogService travelCatalogService;

    @Value("${travel.amap.key:}")
    private String amapKey;

    private record CacheEntry(CityCatalog city, Instant createdAt) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Path cacheDir = Paths.get("data", "online-cities");

    private volatile RestClient geoClient;

    public OnlineCityService(LlmClient llmClient, ObjectMapper objectMapper,
                             TravelCatalogService travelCatalogService) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.travelCatalogService = travelCatalogService;
    }

    /** 获取城市目录：内置目录未命中时联网生成（内存 24h + 本地文件 7 天） */
    public CityCatalog getOrLoad(String rawName) {
        String name = cleanName(rawName);
        if (name.isEmpty() || GENERIC_WORDS.contains(name)) {
            return null;
        }
        // 模糊命中缓存（"石家庄市" ↔ "石家庄"）
        for (CacheEntry entry : cache.values()) {
            String known = entry.city().name();
            if (known.contains(name) || name.contains(known)) {
                return entry.city();
            }
        }
        CacheEntry entry = cache.get(name);
        if (entry != null && entry.createdAt().plus(TTL).isAfter(Instant.now())) {
            return entry.city();
        }
        CityCatalog cached = loadFromDisk(name);
        if (cached != null) {
            log.info("命中本地城市缓存: {}", cached.name());
            return cached;
        }
        CityCatalog city = amapKeyUsable() ? loadViaAmap(name) : null;
        if (city == null) {
            city = loadViaLlmCoords(name);
        }
        if (city != null) {
            cache.put(name, new CacheEntry(city, Instant.now()));
            log.info("联网生成城市目录成功: {} ({}景点/{}酒店/{}餐厅)", city.name(),
                    city.attractions().size(), city.hotels().size(), city.restaurants().size());
            return city;
        }
        return null;
    }

    private boolean amapKeyUsable() {
        return amapKey != null && !amapKey.isBlank();
    }

    /** 轻量定位：仅解析城市坐标（高德优先，Nominatim 兜底），不生成完整目录。失败返回 null。 */
    public double[] quickLocate(String rawName) {
        String name = cleanName(rawName);
        if (name.isEmpty() || GENERIC_WORDS.contains(name)) {
            return null;
        }
        if (amapKeyUsable()) {
            double[] c = amapGeocode(name, null);
            if (c != null) {
                return c;
            }
        }
        try {
            String uri = "https://nominatim.openstreetmap.org/search?q="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&format=json&limit=1&accept-language=zh-CN";
            JsonNode[] arr = geoClient().get().uri(URI.create(uri)).retrieve().body(JsonNode[].class);
            if (arr != null && arr.length > 0) {
                double lat = Double.parseDouble(arr[0].path("lat").asText("0"));
                double lng = Double.parseDouble(arr[0].path("lon").asText("0"));
                if (lat != 0 && lng != 0) {
                    return new double[]{lat, lng};
                }
            }
        } catch (Exception e) {
            log.info("Nominatim 定位不可达（已跳过）: {}", name);
        }
        return null;
    }

    // ==================== 快速路径：精简生成 + 高德批量定位 ====================

    private CityCatalog loadViaAmap(String name) {
        long start = System.currentTimeMillis();
        try {
            // 1. 高德定位城市本身（校验城市存在）
            double[] center = amapGeocode(name, null);
            if (center == null) {
                log.warn("高德定位城市失败，退回兜底路径: {}", name);
                return null;
            }
            // 2. 大模型精简生成（紧凑 JSON，无坐标无描述）
            String raw = llmClient.chat(List.of(
                    LlmMessage.system("你只输出 JSON，不要任何解释、注释或代码块标记。"),
                    LlmMessage.user(compactPrompt(name))), 0.3);
            if (raw == null) {
                return null;
            }
            JsonNode node = objectMapper.readTree(stripFences(raw));

            // 3. 高德批量解析所有 POI 坐标
            List<String> poiNames = new ArrayList<>();
            node.path("attractions").forEach(n -> poiNames.add(n.path("n").asText("")));
            node.path("hotels").forEach(n -> poiNames.add(n.path("n").asText("")));
            node.path("restaurants").forEach(n -> poiNames.add(n.path("n").asText("")));
            poiNames.removeIf(String::isBlank);
            Map<String, double[]> coords = amapBatchGeocode(poiNames, name);
            log.info("高德定位完成: {} 个 POI / {} 个成功，累计 {} ms",
                    poiNames.size(), coords.size(), System.currentTimeMillis() - start);

            // 4. 组装目录（定位失败的 POI 直接丢弃）
            String cityName = node.path("city").asText(name);
            List<Attraction> attractions = new ArrayList<>();
            int[] ai = {0};
            node.path("attractions").forEach(n -> {
                String poiName = n.path("n").asText("").trim();
                double[] loc = resolveCoords(coords, poiName);
                if (poiName.isEmpty() || loc == null || attractions.size() >= 8) return;
                String venue = "i".equalsIgnoreCase(n.path("v").asText("o")) ? "indoor" : "outdoor";
                List<String> tags = new ArrayList<>();
                n.path("t").forEach(t -> {
                    String s = t.asText("").trim();
                    if (!s.isEmpty()) tags.add(s);
                });
                if (tags.isEmpty()) tags.add(venue.equals("indoor") ? "室内" : "景点");
                double ticket = Math.max(n.path("p").asDouble(0), 0);
                attractions.add(new Attraction("ON-" + name + "-A" + (++ai[0]), poiName, venue, tags,
                        loc[0], loc[1], ticket, 2, rating(ai[0]), descFor(venue, ticket)));
            });
            List<Hotel> hotels = new ArrayList<>();
            int[] hi = {0};
            node.path("hotels").forEach(n -> {
                String poiName = n.path("n").asText("").trim();
                double[] loc = resolveCoords(coords, poiName);
                if (poiName.isEmpty() || loc == null || hotels.size() >= 4) return;
                double price = Math.min(Math.max(n.path("p").asDouble(320), 120), 2000);
                hotels.add(new Hotel("ON-" + name + "-H" + (++hi[0]), poiName,
                        n.path("m").asBoolean(false) || n.path("m").asInt(0) == 1,
                        price, rating(hi[0]), loc[0], loc[1],
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
                restaurants.add(new Restaurant("ON-" + name + "-R" + (++ri[0]), poiName, cuisine,
                        cost, rating(ri[0]), loc[0], loc[1],
                        cuisine + "，人均约 ¥" + (int) cost + "，口味以现场体验为准。"));
            });

            CityCatalog catalog = new CityCatalog(cityName, attractions, hotels, restaurants);
            if (catalog.attractions().size() >= 4 && !catalog.hotels().isEmpty()
                    && catalog.restaurants().size() >= 2) {
                travelCatalogService.register(catalog);
                saveToDisk(name, catalog);
                log.info("快速路径完成: {} 共 {} ms", name, System.currentTimeMillis() - start);
                return catalog;
            }
            log.warn("快速路径数据不足: {} ({}景点/{}酒店/{}餐厅)，退回兜底路径",
                    name, attractions.size(), hotels.size(), restaurants.size());
            return null;
        } catch (Exception e) {
            log.warn("高德快速路径失败: {} - {}", name, e.getMessage());
            return null;
        }
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

    private String compactPrompt(String name) {
        return """
                为城市「%s」生成旅行数据，全部使用真实存在的知名地点名称。仅输出 JSON：
                {"city":"%s","attractions":[{"n":"景点名","v":"o","t":["历史","地标"],"p":0},...6个],"hotels":[{"n":"酒店名","m":1,"p":350},...3个],"restaurants":[{"n":"餐厅名","c":"菜系","p":60},...4个]}
                说明：v 取 o(室外)或 i(室内)；t 为 2 个中文标签；p 为大致价格元(免费填0)；m=1 表示近地铁；hotel 用经济型/中端连锁，restaurant 用有本地特色的老字号或名店。
                """.formatted(name, name);
    }

    // ==================== 兜底路径：LLM 直接生成含坐标目录 ====================

    private CityCatalog loadViaLlmCoords(String name) {
        // 并行执行：联网定位（失败/超时不阻塞）+ 大模型生成目录（关键路径）
        CompletableFuture<double[]> geoFuture = CompletableFuture.supplyAsync(() -> amapGeocode(name, null));
        CompletableFuture<String> llmFuture = CompletableFuture.supplyAsync(() -> {
            if (!llmClient.available()) {
                return null;
            }
            return llmClient.chat(List.of(
                    LlmMessage.system("你只输出 JSON，不要输出任何解释或其他内容。"),
                    LlmMessage.user(fullPrompt(name))), 0.4);
        });

        String raw;
        try {
            raw = llmFuture.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("LLM 生成城市目录失败: {} - {}", name, e.getMessage());
            return null;
        }
        try {
            double[] center = geoFuture.get(10, TimeUnit.SECONDS);
            if (center != null) {
                log.info("定位完成: {} -> ({}, {})", name, center[0], center[1]);
            }
        } catch (Exception ignored) {
            // 定位失败不影响生成结果
        }
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(stripFences(raw));
            String cityName = node.path("name").asText(name);
            List<Attraction> attractions = parseAttractions(node.path("attractions"), name);
            List<Hotel> hotels = parseHotels(node.path("hotels"), name);
            List<Restaurant> restaurants = parseRestaurants(node.path("restaurants"), name);
            CityCatalog catalog = new CityCatalog(cityName, attractions, hotels, restaurants);
            if (catalog.attractions().size() >= 4 && !catalog.hotels().isEmpty()
                    && catalog.restaurants().size() >= 2) {
                travelCatalogService.register(catalog);
                saveToDisk(name, catalog);
                return catalog;
            }
            log.warn("在线城市目录数据不足: {} ({}景点/{}酒店/{}餐厅)", name,
                    attractions.size(), hotels.size(), restaurants.size());
            return null;
        } catch (Exception e) {
            log.warn("在线生成城市目录失败: {} - {}", name, e.getMessage());
            return null;
        }
    }

    private String fullPrompt(String name) {
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

    // ==================== 高德地理编码 API ====================

    /** 单点定位；cityHint 可选（限定城市提高准确率）。返回 [lat, lng] */
    private double[] amapGeocode(String address, String cityHint) {
        try {
            JsonNode resp = geoClient().get().uri(URI.create(amapGeoUri(
                    URLEncoder.encode(address, StandardCharsets.UTF_8), cityHint))).retrieve().body(JsonNode.class);
            return firstLocation(resp);
        } catch (Exception e) {
            log.info("高德定位失败: {} - {}", address, e.getMessage());
            return null;
        }
    }

    /** 批量定位（每批最多 10 个，按传入顺序对齐返回）；失败退回逐个查询 */
    private Map<String, double[]> amapBatchGeocode(List<String> names, String city) {
        Map<String, double[]> result = new LinkedHashMap<>();
        for (int start = 0; start < names.size(); start += 10) {
            List<String> chunk = names.subList(start, Math.min(start + 10, names.size()));
            String joined = chunk.stream()
                    .map(n -> URLEncoder.encode(n, StandardCharsets.UTF_8))
                    .collect(Collectors.joining("%7C"));
            try {
                JsonNode resp = geoClient().get().uri(URI.create(amapGeoUri(joined, city))).retrieve().body(JsonNode.class);
                JsonNode geocodes = resp == null ? null : resp.path("geocodes");
                if (resp != null && "1".equals(resp.path("status").asText())
                        && geocodes.isArray() && geocodes.size() == chunk.size()) {
                    for (int i = 0; i < geocodes.size(); i++) {
                        double[] loc = parseLocation(geocodes.get(i).path("location").asText(""));
                        if (loc != null) {
                            result.put(chunk.get(i), loc);
                        }
                    }
                    continue;
                }
            } catch (Exception e) {
                log.info("高德批量定位失败，退回逐个查询: {}", e.getMessage());
            }
            // 数量不对齐或请求失败：逐个补齐
            for (String n : chunk) {
                double[] loc = amapGeocode(n, city);
                if (loc != null) {
                    result.put(n, loc);
                }
            }
        }
        return result;
    }

    private String amapGeoUri(String encodedAddress, String cityHint) {
        String uri = "https://restapi.amap.com/v3/geocode/geo?batch=true&address=" + encodedAddress
                + "&key=" + amapKey;
        if (cityHint != null && !cityHint.isBlank()) {
            uri += "&city=" + URLEncoder.encode(cityHint, StandardCharsets.UTF_8);
        }
        return uri;
    }

    private double[] firstLocation(JsonNode resp) {
        JsonNode geocodes = resp == null ? null : resp.path("geocodes");
        if (geocodes != null && geocodes.isArray() && geocodes.size() > 0) {
            return parseLocation(geocodes.get(0).path("location").asText(""));
        }
        return null;
    }

    /** 高德 location 格式为 "经度,纬度" */
    private double[] parseLocation(String location) {
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            return (lat != 0 && lng != 0) ? new double[]{lat, lng} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private RestClient geoClient() {
        if (geoClient == null) {
            synchronized (this) {
                if (geoClient == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(3000);
                    factory.setReadTimeout(5000);
                    geoClient = RestClient.builder()
                            .requestFactory(factory)
                            .defaultHeader("User-Agent", "travel-agent-demo/0.1 (educational demo)")
                            .build();
                }
            }
        }
        return geoClient;
    }

    // ==================== 兜底路径的 JSON -> 模型（带默认值与脏数据过滤） ====================

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
            List<String> tags = new ArrayList<>();
            n.path("tags").forEach(t -> {
                String s = t.asText("").trim();
                if (!s.isEmpty()) tags.add(s);
            });
            if (tags.isEmpty()) tags.add(venue.equals("indoor") ? "室内" : "景点");
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

    private double normalizeRating(double rating) {
        if (rating <= 0 || rating > 5) {
            return 4.2;
        }
        return Math.round(rating * 10) / 10.0;
    }

    private String cleanName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("(市|地区)$", "").trim();
    }

    private String stripFences(String raw) {
        return raw.replace("```json", "").replace("```", "").trim();
    }

    private double rating(int index) {
        return 4.3 + (index * 7 % 5) / 10.0;
    }

    private String descFor(String venue, double ticket) {
        String base = "indoor".equals(venue) ? "室内场馆，适合雨天与亲子安排。" : "户外景点，出行前请留意天气。";
        return base + (ticket > 0 ? "门票约 ¥" + (int) ticket + "，以现场为准。" : "免费开放，以现场公告为准。");
    }

    // ==================== 本地文件缓存 ====================

    private CityCatalog loadFromDisk(String name) {
        try {
            Path file = cacheDir.resolve(sanitize(name) + ".json");
            if (!Files.exists(file)) {
                return null;
            }
            JsonNode wrapper = objectMapper.readTree(file.toFile());
            Instant savedAt = Instant.parse(wrapper.path("savedAt").asText(Instant.EPOCH.toString()));
            if (savedAt.plus(Duration.ofDays(7)).isBefore(Instant.now())) {
                return null;
            }
            CityCatalog catalog = objectMapper.treeToValue(wrapper.path("city"), CityCatalog.class);
            if (catalog != null && !catalog.attractions().isEmpty()) {
                travelCatalogService.register(catalog);
                cache.put(name, new CacheEntry(catalog, Instant.now()));
                return catalog;
            }
        } catch (Exception e) {
            log.warn("读取本地城市缓存失败: {} - {}", name, e.getMessage());
        }
        return null;
    }

    private void saveToDisk(String name, CityCatalog catalog) {
        try {
            Files.createDirectories(cacheDir);
            Map<String, Object> wrapper = Map.of("savedAt", Instant.now().toString(), "city", catalog);
            objectMapper.writeValue(cacheDir.resolve(sanitize(name) + ".json").toFile(), wrapper);
        } catch (Exception e) {
            log.warn("写入本地城市缓存失败: {} - {}", name, e.getMessage());
        }
    }

    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]", "");
    }
}
