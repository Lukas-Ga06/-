package com.travel.agent.util;

import java.util.Collection;
import java.util.Optional;

/**
 * 城市名匹配规则（纯静态、无 Spring 依赖、可单测）。
 *
 * <p>规则（与 {@code OnlineCityService.matchesCity} 原有语义逐字一致，本类是其唯一实现）：
 * <ol>
 *   <li>双方 {@link #normalize(String)} 后非空；</li>
 *   <li>完全相等 → 命中；</li>
 *   <li>一方包含另一方，且<b>较短一方长度 ≥ 2</b> → 命中；</li>
 *   <li>单字（「阳」「州」「北」）任何时候都不得命中任何城市；</li>
 *   <li>{@link #nearest} 仅用于目录内城市的 typo 兜底：归一后<b>等长、恰好 1 处不同且不在首位</b>，
 *       等距多解返回空（不猜）；首位笔误一律不兜底，避免吞掉目录外的真实城市。</li>
 * </ol>
 */
public final class CityNames {

    /**
     * 可剥离的城市名后缀。
     *
     * <p>与 {@code OnlineCityService.cleanName} 的集合对齐，并补上契约要求的
     * 「特别行政区」「自治州」：只剥这些完整后缀，绝不剥「区/县/镇/路」等更细的地名后缀。
     * 长后缀必须整体匹配，否则「香港特别行政区」会被剥成「香港特别行政」。
     */
    private static final String[] SUFFIXES = {"特别行政区", "自治州", "地区", "市"};

    private CityNames() {
    }

    /**
     * 归一化城市名：去首尾空白，并去掉「市/地区/特别行政区/自治州」等后缀。
     * null 返回空串（调用方无需再判空）。
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.trim();
        for (String suffix : SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                normalized = normalized.substring(0, normalized.length() - suffix.length()).trim();
                break;
            }
        }
        return normalized;
    }

    /**
     * 城市名匹配：完全相等，或一方包含另一方且较短一方长度 ≥ 2。
     * 任一为 null/空白、或较短一方只有 1 个字，一律不命中。
     */
    public static boolean matches(String query, String name) {
        String left = normalize(query);
        String right = normalize(name);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        boolean leftIsShorter = length(left) <= length(right);
        String shorter = leftIsShorter ? left : right;
        String longer = leftIsShorter ? right : left;
        return length(shorter) >= 2 && longer.contains(shorter);
    }

    /**
     * 在候选名集合里取「命中且名字最长」的一个；无命中返回 null。
     * 等长多解（不同城市名归一后长度相同且都命中）返回 null——不按集合顺序猜。
     */
    public static String longestMatch(String text, Collection<String> names) {
        if (text == null || names == null || names.isEmpty()) {
            return null;
        }
        String best = null;
        String bestNormalized = null;
        int bestLength = -1;
        boolean ambiguous = false;
        for (String name : names) {
            if (!matches(text, name)) {
                continue;
            }
            String normalized = normalize(name);
            int candidateLength = length(normalized);
            if (candidateLength > bestLength) {
                best = name;
                bestNormalized = normalized;
                bestLength = candidateLength;
                ambiguous = false;
            } else if (candidateLength == bestLength && !normalized.equals(bestNormalized)) {
                // 不同城市、同样长且都命中：无法判断用户指哪个
                ambiguous = true;
            }
        }
        return ambiguous ? null : best;
    }

    /**
     * typo 兜底：返回集合中与 query 构成「同一城市单字笔误」的唯一候选名。
     *
     * <p>收紧规则（只有这一种笔误算数）：
     * <ul>
     *   <li>归一后<b>长度相同</b>；</li>
     *   <li>逐字符比较<b>恰好 1 处不同</b>，且不同位置 {@code index != 0}；</li>
     *   <li>完全相等（0 处不同）照常命中；长度小于 2 的不参与；</li>
     *   <li>命中多解（等距/等长且都只差一处）返回 {@link Optional#empty()}，不猜。</li>
     * </ul>
     *
     * <p><b>首位笔误一律不兜底</b>：首字替换与「目录外真实城市」无法区分——
     * 「广州/苏州/常州/福州」到「杭州」、「南京/东京」到「北京」都只差首字，
     * 若兜底就会把「去广州」静默规划成杭州行程，比「不支持该目的地」兜底严重得多。
     * 因此只认非首位（如「杭洲」→杭州、「石家庒」→石家庄）。
     *
     * @param maxDistance 允许的最大差异处数（本实现只支持 0 或 1；传入 ≥1 才允许单字笔误）
     */
    public static Optional<String> nearest(String query, Collection<String> names, int maxDistance) {
        if (query == null || names == null || maxDistance < 0) {
            return Optional.empty();
        }
        String normalizedQuery = normalize(query);
        if (length(normalizedQuery) < 2) {
            return Optional.empty();
        }
        int[] queryPoints = normalizedQuery.codePoints().toArray();
        String best = null;
        String bestNormalized = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean ambiguous = false;
        for (String name : names) {
            String normalizedName = normalize(name);
            if (length(normalizedName) < 2) {
                continue;
            }
            int[] namePoints = normalizedName.codePoints().toArray();
            if (queryPoints.length != namePoints.length) {
                // 插入/删除型差异不兜底：无法与另一个城市名区分
                continue;
            }
            int differentIndex = -1;
            int differentCount = 0;
            for (int i = 0; i < queryPoints.length; i++) {
                if (queryPoints[i] != namePoints[i]) {
                    differentCount++;
                    if (differentIndex < 0) {
                        differentIndex = i;
                    }
                    if (differentCount > 1) {
                        break;
                    }
                }
            }
            if (differentCount > 1) {
                continue;
            }
            if (differentCount == 1 && differentIndex == 0) {
                // 首位笔误不兜底：广州/苏州/常州→杭州、南京/东京→北京 都是这种情况
                continue;
            }
            int distance = differentCount;
            if (distance > maxDistance) {
                continue;
            }
            if (distance < bestDistance) {
                best = name;
                bestNormalized = normalizedName;
                bestDistance = distance;
                ambiguous = false;
            } else if (distance == bestDistance && !normalizedName.equals(bestNormalized)) {
                ambiguous = true;
            }
        }
        return ambiguous ? Optional.empty() : Optional.ofNullable(best);
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }
}