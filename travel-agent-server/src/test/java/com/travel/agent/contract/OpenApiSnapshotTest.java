package com.travel.agent.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * OpenAPI 契约快照测试：把 {@code GET /v3/api-docs} 的输出固化到 {@code docs/api/openapi.json}。
 *
 * <p>两种运行模式（只差一个系统属性）：
 * <ul>
 *   <li><b>校验（默认，CI 用）</b>：读取已提交的快照，与当前代码生成的文档做<b>语义</b>比较
 *       （两边都先解析成 JSON 再比，空白/换行差异不算变更）。不一致时打印可定位的差异路径并失败。</li>
 *   <li><b>更新</b>：{@code mvn -B -Dtest=OpenApiSnapshotTest -Dopenapi.update=true test}
 *       把当前文档的规范化结果写入快照。规范化 = 递归按 key 排序 + 2 空格缩进 + UTF-8 + 结尾换行，
 *       因此同一份代码连续生成两次字节完全一致（由 {@link #apiDocsCanonicalizationIsRepeatable()} 保证）。</li>
 * </ul>
 *
 * <p>为什么用 RANDOM_PORT 真容器而不是 MockMvc：契约必须覆盖真实的安全过滤链与 HTTP 层
 * （包括 `/v3/api-docs/**` 的匿名放行规则），MockMvc 手搭的链可能掩盖配置漂移。
 *
 * <p>快照路径解析：从工作目录向上找同时包含 {@code docs/} 与 {@code travel-agent-server/pom.xml}
 * 的仓库根，因此无论从仓库根还是模块目录运行 Maven 都写到同一个位置；
 * 也可用 {@code -Dopenapi.snapshot=<path>} 显式指定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiSnapshotTest {

    private static final String API_DOCS_PATH = "/v3/api-docs";

    /** 核心 schema：这些名字变了说明前端类型会跟着断，必须人工确认而不是悄悄更新快照。 */
    private static final List<String> CORE_SCHEMAS = List.of(
            "TripResponse",
            "ItineraryItemDTO",
            "ChatRequest",
            "AgentResult");

    /** 核心路径：少一条都意味着前端主要流程缺少契约。 */
    private static final List<String> CORE_PATHS = List.of(
            "/api/trips",
            "/api/trips/{id}",
            "/api/trips/{id}/itinerary",
            "/api/agent/chat",
            "/api/auth/login");

    /** 规范化用的 mapper：Map 条目按 key 排序，缩进 2 空格。 */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("/v3/api-docs 可匿名访问，且包含核心 paths/schemas")
    void apiDocsIsServedAndContainsCoreContract() throws Exception {
        JsonNode raw = fetchApiDocs();
        JsonNode canonical = canonicalize(raw);

        JsonNode paths = canonical.path("paths");
        assertThat(paths.isObject() && !paths.isEmpty())
                .as("/v3/api-docs 必须输出非空 paths（Controller 是否被扫描到？）")
                .isTrue();

        List<String> missingPaths = CORE_PATHS.stream().filter(p -> !paths.has(p)).toList();
        assertThat(missingPaths)
                .as("核心 path 缺失，契约已破坏；现有 paths 见下方列表")
                .isEmpty();

        JsonNode schemas = canonical.path("components").path("schemas");
        assertThat(schemas.isObject() && !schemas.isEmpty())
                .as("/v3/api-docs 必须输出非空 components.schemas")
                .isTrue();

        List<String> schemaNames = fieldNames(schemas);
        List<String> missingSchemas = CORE_SCHEMAS.stream().filter(s -> !schemas.has(s)).toList();
        if (!missingSchemas.isEmpty()) {
            fail("核心 schema 缺失: %s%n当前可用 schema (%d): %s"
                    .formatted(missingSchemas, schemaNames.size(), schemaNames));
        }

        // 文档元数据来自 OpenApiConfig：版本漂移说明契约版本与 pom 版本脱节，同样要拦下
        assertThat(canonical.path("openapi").asText())
                .as("必须输出 OpenAPI 3.x 文档")
                .startsWith("3.");
        assertThat(canonical.path("info").path("title").asText()).isNotBlank();
        assertThat(canonical.path("info").path("version").asText())
                .as("信息版本应与 OpenApiConfig.API_VERSION 一致")
                .isEqualTo(com.travel.agent.config.OpenApiConfig.API_VERSION);
    }

    @Test
    @DisplayName("提交的 openapi.json 快照与当前代码生成结果语义一致")
    void committedSnapshotMatchesCurrentApiDocs() throws Exception {
        JsonNode current = canonicalize(fetchApiDocs());
        Path snapshot = snapshotPath();

        if (updateMode()) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, toJson(current) + "\n", StandardCharsets.UTF_8);
            System.out.println("[openapi] 已更新契约快照: " + snapshot
                    + " (paths=" + current.path("paths").size()
                    + ", schemas=" + current.path("components").path("schemas").size() + ")");
            return;
        }

        if (!Files.isRegularFile(snapshot)) {
            fail("契约快照不存在: %s%n首次生成请运行：mvn -B -Dtest=OpenApiSnapshotTest -Dopenapi.update=true test"
                    .formatted(snapshot));
        }

        JsonNode committed = canonicalize(CANONICAL_MAPPER.readTree(Files.readString(snapshot, StandardCharsets.UTF_8)));
        if (!committed.equals(current)) {
            List<String> differences = new ArrayList<>();
            diff("$", committed, current, differences, 120);
            fail("""
                    OpenAPI 契约与快照 docs/api/openapi.json 不一致。

                    若变更是有意的（DTO/接口改动），运行：
                      mvn -B -Dtest=OpenApiSnapshotTest -Dopenapi.update=true test
                    然后重新生成前端类型：cd travel-agent-web && npm run api:generate
                    并按 docs/guide/api-contract.md 检查是否属于破坏性变更。

                    差异（期望=快照，实际=当前代码）：
                    %s""".formatted(String.join(System.lineSeparator(), differences)));
        }
    }

    @Test
    @DisplayName("规范化后的快照可重复：连续两次取文档字节一致")
    void apiDocsCanonicalizationIsRepeatable() throws Exception {
        String first = toJson(canonicalize(fetchApiDocs()));
        String second = toJson(canonicalize(fetchApiDocs()));
        assertThat(second)
                .as("两次生成的规范化 JSON 必须字节一致，否则快照会随机漂移")
                .isEqualTo(first);
    }

    // ==================== HTTP / 快照路径 ====================

    private JsonNode fetchApiDocs() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(API_DOCS_PATH, String.class);
        assertThat(response.getStatusCode())
                .as("GET %s 必须匿名可访问（SecurityConfig 需放行该路径）", API_DOCS_PATH)
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("api-docs 响应体不能为空").isNotBlank();
        return CANONICAL_MAPPER.readTree(response.getBody());
    }

    private static boolean updateMode() {
        String property = System.getProperty("openapi.update",
                System.getenv().getOrDefault("OPENAPI_UPDATE", "false"));
        return Boolean.parseBoolean(property);
    }

    /** 快照位置：默认 {@code <repo>/docs/api/openapi.json}，可用 {@code -Dopenapi.snapshot=} 覆盖。 */
    private static Path snapshotPath() {
        String override = System.getProperty("openapi.snapshot",
                System.getenv().getOrDefault("OPENAPI_SNAPSHOT", ""));
        if (!override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && dir != null; depth++) {
            if (Files.isDirectory(dir.resolve("docs"))
                    && Files.isRegularFile(dir.resolve("travel-agent-server").resolve("pom.xml"))) {
                return dir.resolve("docs").resolve("api").resolve("openapi.json");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（含 docs/ 与 travel-agent-server/pom.xml），"
                + "请用 -Dopenapi.snapshot=<path> 指定快照位置；当前工作目录="
                + System.getProperty("user.dir"));
    }

    // ==================== 规范化与差异输出 ====================

    /**
     * 递归规范化：对象 key 按字典序排列；顶层 {@code tags} 数组按 name 排序
     * （springdoc 的 tag 顺序来自 bean 扫描顺序，无语义且理论上可能变化）。
     */
    private static JsonNode canonicalize(JsonNode node) {
        return canonicalize(node, "$");
    }

    private static JsonNode canonicalize(JsonNode node, String path) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry ->
                    sorted.put(entry.getKey(), canonicalize(entry.getValue(),
                            path + "." + escapePointer(entry.getKey()))));
            ObjectNode object = CANONICAL_MAPPER.createObjectNode();
            sorted.forEach(object::set);
            return object;
        }
        if (node.isArray()) {
            if ("$.tags".equals(path) && isNamedTagArray(node)) {
                List<JsonNode> tags = new ArrayList<>();
                node.forEach(tag -> tags.add(canonicalize(tag, path + "[]")));
                tags.sort(Comparator.comparing(tag -> tag.path("name").asText()));
                ArrayNode sorted = CANONICAL_MAPPER.createArrayNode();
                tags.forEach(sorted::add);
                return sorted;
            }
            ArrayNode array = CANONICAL_MAPPER.createArrayNode();
            int index = 0;
            for (JsonNode item : node) {
                array.add(canonicalize(item, path + "[" + index++ + "]"));
            }
            return array;
        }
        return node;
    }

    private static boolean isNamedTagArray(JsonNode array) {
        for (JsonNode item : array) {
            if (!item.isObject() || !item.hasNonNull("name")) {
                return false;
            }
        }
        return true;
    }

    private static String toJson(JsonNode node) throws IOException {
        return CANONICAL_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    /** 递归比较，输出「路径：快照值 != 当前值」，最多保留 {@code limit} 条。 */
    private static void diff(String path, JsonNode expected, JsonNode actual, List<String> out, int limit) {
        if (out.size() >= limit) {
            return;
        }
        if (expected.equals(actual)) {
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            TreeSet<String> keys = new TreeSet<>(fieldNames(expected));
            keys.addAll(fieldNames(actual));
            for (String key : keys) {
                if (out.size() >= limit) {
                    return;
                }
                String child = path + "." + escapePointer(key);
                if (!expected.has(key)) {
                    out.add(child + ": 快照缺失，当前代码新增 " + abbreviate(actual.get(key)));
                } else if (!actual.has(key)) {
                    out.add(child + ": 当前代码已删除（快照值 " + abbreviate(expected.get(key)) + "）");
                } else {
                    diff(child, expected.get(key), actual.get(key), out, limit);
                }
            }
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                out.add(path + ": 数组长度 快照=" + expected.size() + " 当前=" + actual.size());
            }
            int size = Math.min(expected.size(), actual.size());
            for (int i = 0; i < size && out.size() < limit; i++) {
                diff(path + "[" + i + "]", expected.get(i), actual.get(i), out, limit);
            }
            return;
        }
        out.add(path + ": 快照=" + abbreviate(expected) + " 当前=" + abbreviate(actual));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String escapePointer(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static String abbreviate(JsonNode node) {
        String text = node.isValueNode() ? node.asText() : node.toString();
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }
}