package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Hotel;
import com.travel.agent.model.Restaurant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结构化规划的规则引擎：模型只允许从候选清单选择，坐标/价格以目录覆盖，非法条目丢弃 */
class StructuredPlannerTest {

    private final StructuredPlanner planner = new StructuredPlanner(null, new ObjectMapper());

    private final CityCatalog city = new CityCatalog(
            "测试市",
            List.of(
                    new Attraction("a1", "故宫", "outdoor", List.of("历史"), 39.9, 116.4, 60, 2, 4.8, "明清皇宫"),
                    new Attraction("a2", "科技馆", "indoor", List.of("亲子"), 39.8, 116.5, 30, 2.5, 4.6, "科技展览"),
                    new Attraction("a3", "博物馆", "indoor", List.of("文化"), 39.7, 116.3, 0, 2, 4.5, "免费")),
            List.of(new Hotel("h1", "城心酒店", true, 400, 4.7, 39.95, 116.42, "市中心")),
            List.of(new Restaurant("r1", "老字号烤鸭", "京菜", 120, 4.6, 39.91, 116.41, "烤鸭")));

    private final StructuredPlanner.TripDraftView draft =
            new StructuredPlanner.TripDraftView("测试市", 3, LocalDate.of(2026, 11, 1),
                    BigDecimal.valueOf(3000), List.of());

    @Test
    void validItineraryConvertsWithCatalogFacts() throws Exception {
        String json = """
                {"reasoning":"经典搭配","days":[
                  {"day":1,"items":[
                    {"type":"hotel","name":"城心酒店","startTime":"09:00","endTime":"09:30"},
                    {"type":"attraction","name":"故宫","startTime":"10:00","endTime":"11:45"},
                    {"type":"restaurant","name":"老字号烤鸭","startTime":"12:00","endTime":"13:00"}]},
                  {"day":2,"items":[{"type":"attraction","name":"科技馆","startTime":"09:00","endTime":"11:00"}]}]}
                """;
        StructuredPlanner.NewTripParse parse = planner.parseNewTrip(city, draft, json);
        assertTrue(parse.ok());
        assertEquals(4, parse.drafts().size());
        assertTrue(parse.warnings().isEmpty());
        assertEquals("经典搭配", parse.reasoning());

        ItineraryItem hotel = parse.drafts().get(0);
        assertEquals(ItineraryItem.TYPE_HOTEL, hotel.getItemType());
        assertEquals(0, BigDecimal.valueOf(800).compareTo(hotel.getCost()), "酒店费用 = 400 × (3-1)");
        assertEquals(39.95, hotel.getLatitude());

        ItineraryItem attraction = parse.drafts().get(1);
        assertEquals(ItineraryItem.TYPE_ATTRACTION, attraction.getItemType());
        assertEquals(0, BigDecimal.valueOf(60).compareTo(attraction.getCost()), "门票以目录为准，忽略模型报价");
        assertTrue(attraction.getTags().contains("室外"), "室内外标记来自目录");
        assertEquals(LocalDate.of(2026, 11, 1), attraction.getStartTime().toLocalDate(), "条目时间对齐出发日期");
    }

    @Test
    void fabricatedNamesAndBadDaysAreDropped() throws Exception {
        String json = """
                {"reasoning":"r","days":[
                  {"day":1,"items":[
                    {"type":"attraction","name":"不存在的乐园","startTime":"09:00","endTime":"10:00"},
                    {"type":"attraction","name":"故宫","startTime":"10:00","endTime":"11:45"}]},
                  {"day":9,"items":[{"type":"attraction","name":"科技馆","startTime":"09:00","endTime":"10:00"}]}]}
                """;
        StructuredPlanner.NewTripParse parse = planner.parseNewTrip(city, draft, json);
        assertTrue(parse.ok(), "有效条目仍保留");
        assertEquals(1, parse.drafts().size(), "编造名称与越界天数被丢弃");
        assertEquals("故宫", parse.drafts().get(0).getTitle());
        assertEquals(2, parse.warnings().size());
    }

    @Test
    void illegalTimesAndTransportAreRejected() throws Exception {
        String json = """
                {"reasoning":"r","days":[{"day":1,"items":[
                  {"type":"attraction","name":"故宫","startTime":"14:00","endTime":"10:00"},
                  {"type":"attraction","name":"故宫","startTime":"昨天","endTime":"11:00"},
                  {"type":"transport","name":"出租车","startTime":"20:00","endTime":"20:30"}]}]}
                """;
        StructuredPlanner.NewTripParse parse = planner.parseNewTrip(city, draft, json);
        assertTrue(parse.drafts().isEmpty(), "时间倒挂/非法时间/transport 均不接受");
    }

    @Test
    void malformedOutputReturnsFailure() throws Exception {
        assertThrows(Exception.class, () -> planner.parseNewTrip(city, draft, "这不是 JSON"));
        assertNull(planner.parseNewTrip(city, draft, "{\"reasoning\":\"空计划\"}"));
    }

    @Test
    void hotelOnlyAllowedOnFirstDay() throws Exception {
        String json = """
                {"reasoning":"r","days":[{"day":2,"items":[
                  {"type":"hotel","name":"城心酒店","startTime":"09:00","endTime":"09:30"}]}]}
                """;
        StructuredPlanner.NewTripParse parse = planner.parseNewTrip(city, draft, json);
        assertTrue(parse.drafts().isEmpty());
        assertTrue(parse.warnings().get(0).contains("第 1 天"));
    }

    @Test
    void modificationMatchesExistingTitlesUniquely() throws Exception {
        ItineraryDtos.ItineraryItemDTO existing = new ItineraryDtos.ItineraryItemDTO();
        existing.setId(11L);
        existing.setDayIndex(1);
        existing.setItemType(ItineraryItem.TYPE_ATTRACTION);
        existing.setTitle("故宫");
        String json = """
                {"reasoning":"雨天换室内","operations":[
                  {"type":"REMOVE","title":"故宫"},
                  {"type":"ADD","name":"科技馆","dayIndex":1,"startTime":"14:00","endTime":"16:30"}]}
                """;
        StructuredPlanner.ModificationParse parse =
                planner.parseModification(city, List.of(existing), LocalDate.of(2026, 11, 1), json);
        assertTrue(parse.ok());
        assertEquals(11L, parse.removes().get(0).target().getId());
        assertEquals("科技馆", parse.adds().get(0).getTitle());
        assertEquals(0, BigDecimal.valueOf(30).compareTo(parse.adds().get(0).getCost()));
        assertTrue(parse.adds().get(0).getTags().contains("室内"));
        assertEquals("雨天换室内", parse.reasoning());
    }

    @Test
    void modificationLoosePrefixMatchAndMisses() throws Exception {
        ItineraryDtos.ItineraryItemDTO lunch = new ItineraryDtos.ItineraryItemDTO();
        lunch.setId(7L);
        lunch.setDayIndex(1);
        lunch.setTitle("午餐 · 老字号烤鸭");
        ItineraryDtos.ItineraryItemDTO dup = new ItineraryDtos.ItineraryItemDTO();
        dup.setId(8L);
        dup.setDayIndex(2);
        dup.setTitle("老字号烤鸭");
        String json = """
                {"reasoning":"r","operations":[
                  {"type":"REMOVE","title":"老字号烤鸭"},
                  {"type":"REMOVE","title":"没有这个安排"},
                  {"type":"ADD","name":"博物馆","dayIndex":1,"startTime":"14:00","endTime":"16:00"}]}
                """;
        StructuredPlanner.ModificationParse parse =
                planner.parseModification(city, List.of(lunch, dup), LocalDate.of(2026, 11, 1), json);
        assertTrue(parse.ok());
        assertEquals(1, parse.removes().size(), "精确命中『老字号烤鸭』（id=8）→ 唯一匹配，允许移除");
        assertEquals(8L, parse.removes().get(0).target().getId());
        assertTrue(parse.warnings().stream().anyMatch(w -> w.contains("未在现有行程中找到")));
        assertEquals(1, parse.adds().size());
        assertEquals(0, BigDecimal.ZERO.compareTo(parse.adds().get(0).getCost()), "免费景点票价 0");
    }
}
