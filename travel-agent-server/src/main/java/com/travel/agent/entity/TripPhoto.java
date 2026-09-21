package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 行程照片：文件落在本地磁盘（travel.photos.dir），库里只存元数据 */
@Entity
@Table(name = "trip_photo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 磁盘上的文件名（UUID + 扩展名），不暴露原始文件名 */
    @Column(name = "stored_name", length = 120, nullable = false)
    private String storedName;

    @Column(name = "original_name", length = 200)
    private String originalName;

    @Column(name = "content_type", length = 80)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
