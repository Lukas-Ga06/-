package com.travel.agent.agent;

import com.travel.agent.util.CityNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目的地抽取的回归测试（2026-11-24 灰度期脏城市名）。
 *
 * <p>消息全部来自 {@code .e2e/llm-load-20.mjs} 的真实 20 连压测：修正前其中 6 轮产出
 * 「成都吃」「发去厦门」「张家界的」「雪乡的」「泉州的」等查无此城的串，run-18 还把
 * 「从南京出发去扬州镇江」抽成「州镇江的」并因此生成失败。
 */
class MessageParserCityTest {

    private static final String[][] LOAD_MESSAGES = {
            {"帮我规划上海出发的杭州周末两日游，两人，预算3000以内", "杭州"},
            {"规划一个北京出发去西安的十一五日游，一家三口，预算12000", "西安"},
            {"我在广州，想去成都吃四天，两个人预算6000，帮我做行程", "成都"},
            {"深圳出发去厦门三日游，预算4000，两个人，海边多一点", "厦门"},
            {"规划武汉到长沙的两天一夜，学生党，预算1500", "长沙"},
            {"帮我安排一个南京出发的苏州无锡两日游，预算2500", "苏州"},
            {"从成都出发去重庆的三日游，两个人，想吃火锅和看夜景，预算3500", "重庆"},
            {"规划杭州出发去黄山的四日游，两个人预算8000，想爬山", "黄山"},
            {"我在天津，想去青岛玩三天，预算3000，两个人", "青岛"},
            {"帮我规划西安出发去洛阳开封的两日游，预算2000", "洛阳"},
            {"上海出发去苏州一日游，预算800，一个人，想看园林", "苏州"},
            {"规划北京出发去承德避暑山庄的两日游，一家四口，预算4000", "承德"},
            {"从长沙出发去张家界的五日游，两个人预算9000", "张家界"},
            {"我在昆明，想去大理丽江玩五天，预算7000，两个人", "大理"},
            {"规划哈尔滨出发去雪乡的三日游，两个人，预算5000，冬天出行", "雪乡"},
            {"厦门出发去泉州的两日游，预算1800，两个人，想看人文古迹", "泉州"},
            {"帮我规划郑州出发去开封的周末两日游，带小孩，预算2500", "开封"},
            {"从南京出发去扬州镇江的两日游，预算2200，两个人，想吃淮扬菜", "扬州"},
            // 营口不在城市图集里，且「营口鲅鱼圈」不是一个城市：宁可不猜，交给模型/追问
            {"规划大连出发去营口鲅鱼圈的两天海边游，预算2000", null},
            {"我在宁波，想去舟山群岛玩三天，预算3500，两个人，海鲜多吃点", "舟山"},
    };

    @Test
    @DisplayName("20 连压测的真实消息：目的地不再出现「成都吃」「州镇江的」")
    void extractsDestinationFromLoadMessages() {
        for (String[] item : LOAD_MESSAGES) {
            String message = item[0];
            String expected = item[1];
            assertThat(MessageParser.guessCityName(message))
                    .as(message)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("出发地不是目的地：「从南京出发去杭州」不能抽成南京")
    void originIsNotDestination() {
        assertThat(MessageParser.guessCityName("从南京出发去杭州的三日游")).isEqualTo("杭州");
        assertThat(MessageParser.guessCityName("北京出发去西安五日游")).isEqualTo("西安");
        assertThat(MessageParser.guessCityName("上海出发的杭州周末两日游")).isEqualTo("杭州");
        // 只有出发地、没有目的地：不猜
        assertThat(MessageParser.guessCityName("从上海出发")).isNull();
    }

    @Test
    @DisplayName("多城市目的地取第一个：单次只规划一个城市，但不能产出查无此城的串")
    void multiCityDestinationTakesFirst() {
        assertThat(MessageParser.guessCityName("从南京出发去扬州镇江的两日游")).isEqualTo("扬州");
        assertThat(MessageParser.guessCityName("我在昆明，想去大理丽江玩五天")).isEqualTo("大理");
        assertThat(MessageParser.guessCityName("上海出发去苏州无锡两日游")).isEqualTo("苏州");
    }

    @Test
    @DisplayName("结果必须通过城市名可信校验（不再有句尾助词/动词残留）")
    void resultIsAlwaysPlausibleCityName() {
        for (String[] item : LOAD_MESSAGES) {
            String city = MessageParser.guessCityName(item[0]);
            if (city != null) {
                assertThat(CityNames.isPlausibleName(city)).as(item[0]).isTrue();
            }
        }
    }

    @Test
    @DisplayName("旧规则仍成立：N 日游 / 去 X 两天 / X 行程 / 极短城市回答")
    void keepsExistingStyles() {
        assertThat(MessageParser.guessCityName("帮我规划上海三日游，2人，预算3000元")).isEqualTo("上海");
        assertThat(MessageParser.guessCityName("三个人下周六去北京两天")).isEqualTo("北京");
        assertThat(MessageParser.guessCityName("帮我规划杭州行程")).isEqualTo("杭州");
        assertThat(MessageParser.guessCityName("我想去火星看看")).isEqualTo("火星");
        assertThat(MessageParser.guessCityName("帮我规划行程")).isNull();
        assertThat(MessageParser.guessCityName("预算3000元")).isNull();
    }
}