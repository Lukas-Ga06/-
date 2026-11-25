package com.travel.agent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 受信代理匹配。
 *
 * <p>限流按客户端 IP 计数时，"客户端 IP"必须来自可信来源：只有 TCP 源地址（{@code remoteAddr}）
 * 命中受信代理列表时，才能采信它转发的 {@code X-Forwarded-For}；否则 XFF 完全由客户端控制，
 * 换一个伪造值就换一个计数桶，限流形同虚设。
 *
 * <p>支持两种规则：
 * <ul>
 *   <li>精确 IP（IPv4 / IPv6 文本，忽略大小写与首尾空白）</li>
 *   <li>IPv4 CIDR，如 {@code 172.16.0.0/12}、{@code 10.0.0.0/8}</li>
 * </ul>
 * 非法条目（格式错误、前缀越界、IPv6 CIDR 等）一律忽略（仅记 debug 日志），
 * 避免一个手滑的配置把整个限流打挂。
 */
public final class TrustedProxies {

    private static final Logger log = LoggerFactory.getLogger(TrustedProxies.class);

    private TrustedProxies() {
    }

    /** ip 是否命中任一规则；ip 或规则为空时返回 false（默认不信任任何代理） */
    public static boolean matches(String ip, List<String> rules) {
        if (ip == null || rules == null || rules.isEmpty()) {
            return false;
        }
        String candidate = ip.trim();
        if (candidate.isEmpty()) {
            return false;
        }
        for (String raw : rules) {
            if (raw == null) {
                continue;
            }
            String rule = raw.trim();
            if (rule.isEmpty()) {
                continue;
            }
            int slash = rule.indexOf('/');
            boolean matched;
            if (slash < 0) {
                matched = candidate.equalsIgnoreCase(rule);
            } else {
                matched = matchesIpv4Cidr(candidate, rule.substring(0, slash).trim(), rule.substring(slash + 1).trim());
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesIpv4Cidr(String ip, String network, String prefixText) {
        int prefix;
        try {
            prefix = Integer.parseInt(prefixText);
        } catch (NumberFormatException e) {
            log.debug("忽略非法受信代理规则（CIDR 前缀不是数字）: {}/{}", network, prefixText);
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            log.debug("忽略非法受信代理规则（CIDR 前缀越界）: {}/{}", network, prefixText);
            return false;
        }
        byte[] address = parseIpv4(ip);
        byte[] base = parseIpv4(network);
        if (address == null || base == null) {
            // 日志净化：ip 来自请求头 X-Forwarded-For，必须剥掉 CR/LF 再进日志（防日志伪造）。
            // 这里 ip 已在上面被 parseIpv4 读过，必然非 null，不再做 null 分支
            //（SpotBugs RCN_REDUNDANT_NULLCHECK：再加 null 判断反而是死代码）。
            log.debug("忽略非法受信代理规则（非 IPv4 CIDR）: {}",
                    ip.replace("\r", "_").replace("\n", "_"));
            return false;
        }
        int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
        return (toInt(address) & mask) == (toInt(base) & mask);
    }

    /** 严格解析点分十进制 IPv4；非法返回 null（不做宽松的 inet_aton 式解析） */
    private static byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            for (int j = 0; j < part.length(); j++) {
                if (!Character.isDigit(part.charAt(j))) {
                    return null;
                }
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) {
                return null;
            }
            result[i] = (byte) octet;
        }
        return result;
    }

    private static int toInt(byte[] address) {
        return ((address[0] & 0xFF) << 24)
                | ((address[1] & 0xFF) << 16)
                | ((address[2] & 0xFF) << 8)
                | (address[3] & 0xFF);
    }
}