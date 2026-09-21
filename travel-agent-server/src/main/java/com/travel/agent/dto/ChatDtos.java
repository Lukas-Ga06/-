package com.travel.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class ChatDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        private String message;
        private Long tripId;
        private Integer currentDay;
        private SelectedElement selectedElement;
        private MapLocation mapCenter;
    }

    /** 多模态上下文：用户点击选中的元素，解决「这个换成类似的」中的指代问题 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelectedElement {
        private String type;   // attraction / hotel / restaurant / itinerary_item
        private Long id;
        private String title;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MapLocation {
        private Double lat;
        private Double lng;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmRequest {
        private String planId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UndoRequest {
        private Long tripId;
    }
}
