package com.travel.agent.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 受信代理匹配：精确 IP + IPv4 CIDR，非法条目静默忽略。 */
class TrustedProxiesTest {

    @Test
    void exactIpv4AndIpv6Match() {
        assertTrue(TrustedProxies.matches("10.1.2.3", List.of("10.1.2.3")));
        assertFalse(TrustedProxies.matches("10.1.2.4", List.of("10.1.2.3")));
        // 允许首尾空白与 IPv6 大小写差异
        assertTrue(TrustedProxies.matches(" 10.1.2.3 ", List.of(" 10.1.2.3 ")));
        assertTrue(TrustedProxies.matches("::1", List.of("::1")));
        assertTrue(TrustedProxies.matches("2001:DB8::1", List.of("2001:db8::1")));
    }

    @Test
    void ipv4CidrRangesMatch() {
        List<String> docker = List.of("172.16.0.0/12");
        assertTrue(TrustedProxies.matches("172.16.0.1", docker));
        assertTrue(TrustedProxies.matches("172.31.255.255", docker));
        assertFalse(TrustedProxies.matches("172.32.0.1", docker));
        assertFalse(TrustedProxies.matches("172.15.255.255", docker));

        List<String> privateA = List.of("10.0.0.0/8");
        assertTrue(TrustedProxies.matches("10.255.255.254", privateA));
        assertFalse(TrustedProxies.matches("11.0.0.1", privateA));

        // /32 等价精确匹配，/0 匹配任意 IPv4
        assertTrue(TrustedProxies.matches("192.168.1.1", List.of("192.168.1.1/32")));
        assertFalse(TrustedProxies.matches("192.168.1.2", List.of("192.168.1.1/32")));
        assertTrue(TrustedProxies.matches("203.0.113.9", List.of("0.0.0.0/0")));
    }

    @Test
    void invalidRulesAreIgnoredWithoutThrowing() {
        List<String> rules = List.of("not-an-ip", "10.0.0.0/99", "10.0.0.0/x", "172.16.0.0/-1",
                "300.1.2.3", "::1/64", "");
        assertFalse(TrustedProxies.matches("10.1.2.3", rules));
        assertFalse(TrustedProxies.matches("::1", rules));
    }

    @Test
    void emptyOrNullRulesTrustNothing() {
        assertFalse(TrustedProxies.matches("10.1.2.3", null));
        assertFalse(TrustedProxies.matches("10.1.2.3", List.of()));
        assertFalse(TrustedProxies.matches(null, List.of("10.0.0.0/8")));
        assertFalse(TrustedProxies.matches("", List.of("10.0.0.0/8")));
    }
}