package com.travel.agent.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 内置工具的安全参数读取（P0-B）。
 *
 * <p>模型经常把数字写成字符串（{@code "nearLat":"31.22"}）、把布尔写成 {@code "true"}，
 * 或者用数组而不是逗号分隔串表示排除项。直接强转会抛 ClassCastException，用户只看到
 * 「系统异常」；这里统一做宽容解析，仍然失败时抛出可读的 {@link IllegalArgumentException}，
 * 由 {@link ToolInvoker} 落 tool_call_trace 后向上传播——失败语义与工具自身抛错一致。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    /** 字符串参数：null/空串按默认值处理；其它类型用 String.valueOf 兼容（模型偶尔传数字/布尔） */
    public static String string(Map<String, Object> args, String key) {
        return string(args, key, null);
    }

    public static String string(Map<String, Object> args, String key, String defaultValue) {
        Object raw = args == null ? null : args.get(key);
        if (raw == null) {
            return defaultValue;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    /** 可空长整数：兼容数字与数字字符串 */
    public static Long longValue(Map<String, Object> args, String key) {
        Object raw = args == null ? null : args.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw invalid(key, raw, "整数");
        }
    }

    /** 可空浮点数：兼容数字与数字字符串 */
    public static Double doubleValue(Map<String, Object> args, String key) {
        Object raw = args == null ? null : args.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw invalid(key, raw, "数字");
        }
    }

    /** 必填整数：缺失/空串时给出与 ToolInvoker 一致的「缺少必填参数」文案 */
    public static long requireLong(Map<String, Object> args, String key) {
        Long value = longValue(args, key);
        if (value == null) {
            throw new IllegalArgumentException("缺少必填参数 " + key);
        }
        return value;
    }

    /** 必填浮点数：缺失/空串时给出与 ToolInvoker 一致的「缺少必填参数」文案 */
    public static double requireDouble(Map<String, Object> args, String key) {
        Double value = doubleValue(args, key);
        if (value == null) {
            throw new IllegalArgumentException("缺少必填参数 " + key);
        }
        return value;
    }

    /** 可空布尔：兼容 Boolean、0/1、true/false、yes/no、是/否 */
    public static Boolean booleanValue(Map<String, Object> args, String key) {
        Object raw = args == null ? null : args.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.doubleValue() != 0D;
        }
        String text = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        return switch (text) {
            case "true", "1", "yes", "y", "是" -> Boolean.TRUE;
            case "false", "0", "no", "n", "否" -> Boolean.FALSE;
            default -> throw invalid(key, raw, "布尔值（true/false）");
        };
    }

    /** 字符串列表：兼容 List/数组与逗号（中英文逗号均可）分隔串；空值过滤，顺序保留 */
    public static List<String> stringList(Map<String, Object> args, String key) {
        Object raw = args == null ? null : args.get(key);
        if (raw == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                addNonBlank(values, item);
            }
            return values;
        }
        if (raw instanceof Object[] array) {
            for (Object item : array) {
                addNonBlank(values, item);
            }
            return values;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        for (String token : text.split("[,，]")) {
            addNonBlank(values, token);
        }
        return values;
    }

    private static void addNonBlank(List<String> values, Object item) {
        if (item == null) {
            return;
        }
        String text = String.valueOf(item).trim();
        if (!text.isEmpty()) {
            values.add(text);
        }
    }

    private static IllegalArgumentException invalid(String key, Object raw, String expected) {
        return new IllegalArgumentException("参数 " + key + " 需要" + expected + "，实际为「" + raw + "」");
    }
}
