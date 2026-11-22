package com.travel.agent.dto;

import com.travel.agent.entity.TripPhoto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class PhotoDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Photo {
        private Long id;
        private Long tripId;
        private String originalName;
        private String contentType;
        private Long sizeBytes;
        private String createdAt;
        /** 原图地址；前端需带上 token（EventSource/<img> 无法自定义请求头） */
        private String url;

        public static Photo from(TripPhoto photo) {
            return Photo.builder()
                    .id(photo.getId())
                    .tripId(photo.getTripId())
                    .originalName(photo.getOriginalName())
                    .contentType(photo.getContentType())
                    .sizeBytes(photo.getSizeBytes())
                    .createdAt(photo.getCreatedAt() == null ? null : photo.getCreatedAt().toString())
                    .url("/api/photos/" + photo.getId() + "/raw")
                    .build();
        }
    }
}
