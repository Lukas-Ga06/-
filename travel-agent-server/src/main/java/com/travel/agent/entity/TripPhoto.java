package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 行程照片：文件落在本地磁盘（travel.photos.dir），库里只存元数据 */
@Entity
@Table(name = "trip_photo", indexes = {
        @Index(name = "idx_trip_photo_trip", columnList = "trip_id"),
        @Index(name = "idx_trip_photo_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripPhoto {

    /** 普通照片（默认）：只用于相册展示 */
    public static final String KIND_PHOTO = "PHOTO";
    /** 发票：报销流程取该用户最新一张识别，不新建存储与发票表（V32） */
    public static final String KIND_INVOICE = "INVOICE";

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

    /** 图片用途：{@link #KIND_PHOTO}（默认）/ {@link #KIND_INVOICE}（V32），由 PhotoService 校验取值 */
    @Column(length = 20)
    private String kind;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
