package com.travel.agent;

import com.travel.agent.repository.UserRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 微信公众号渠道：接入签名校验、XML 消息 → AgentEngine → XML 被动回复、账号自动开通 */
@TestPropertySource(properties = "travel.channels.wechat.token=test-oa-token")
class ChannelWeChatTest extends IntegrationTestSupport {

    private static final String TOKEN = "test-oa-token";

    @Autowired
    private UserRepository userRepository;

    @Test
    void verifyEchoesOnlyWithValidSignature() throws Exception {
        String timestamp = "1719999999";
        String nonce = "nonce123";
        MvcResult ok = mockMvc.perform(get("/api/channels/wechat")
                        .param("signature", sign(TOKEN, timestamp, nonce))
                        .param("timestamp", timestamp)
                        .param("nonce", nonce)
                        .param("echostr", "echo-值"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("echo-值", ok.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/channels/wechat")
                        .param("signature", "bad")
                        .param("timestamp", timestamp)
                        .param("nonce", nonce)
                        .param("echostr", "echo"))
                .andExpect(status().isForbidden());
    }

    @Test
    void textMessageRunsThroughEngineAndRepliesXml() throws Exception {
        String openid = "o" + System.nanoTime();
        String xml = """
                <xml><ToUserName><![CDATA[gh_demo]]></ToUserName>
                <FromUserName><![CDATA[%s]]></FromUserName>
                <CreateTime>1719999999</CreateTime>
                <MsgType><![CDATA[text]]></MsgType>
                <Content><![CDATA[记一下明天办签证]]></Content></xml>
                """.formatted(openid);
        MvcResult result = mockMvc.perform(post("/api/channels/wechat")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(xml))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("<![CDATA[gh_demo]]>"), "回复 ToUserName 应为公众号");
        assertTrue(body.contains("已添加备忘"), "回复内容来自 AgentEngine：" + body);
        assertTrue(userRepository.existsByUsername("wx_" + openid), "openid 应自动开通本地账号");
    }

    @Test
    void subscribeEventGetsWelcomeAndUnknownEventsIgnored() throws Exception {
        String openid = "o" + System.nanoTime();
        String subscribe = """
                <xml><FromUserName><![CDATA[%s]]></FromUserName><ToUserName><![CDATA[gh_demo]]></ToUserName>
                <MsgType><![CDATA[event]]></MsgType><Event><![CDATA[subscribe]]></Event></xml>
                """.formatted(openid);
        MvcResult result = mockMvc.perform(post("/api/channels/wechat")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(subscribe))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("<![CDATA[text]]>"), "关注事件应回复欢迎语");

        String click = """
                <xml><FromUserName><![CDATA[%s]]></FromUserName><ToUserName><![CDATA[gh_demo]]></ToUserName>
                <MsgType><![CDATA[event]]></MsgType><Event><![CDATA[CLICK]]></Event></xml>
                """.formatted(openid);
        MvcResult ignored = mockMvc.perform(post("/api/channels/wechat")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(click))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals("", ignored.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void malformedXmlIsIgnoredWithoutError() throws Exception {
        mockMvc.perform(post("/api/channels/wechat")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<xml>这不是有效消息"))
                .andExpect(status().isOk());
    }

    private static String sign(String token, String timestamp, String nonce) throws Exception {
        var parts = new java.util.ArrayList<>(java.util.List.of(token, timestamp, nonce));
        java.util.Collections.sort(parts);
        var digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest(String.join("", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
