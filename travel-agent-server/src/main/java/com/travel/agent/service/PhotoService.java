package com.travel.agent.service;

import com.travel.agent.entity.Trip;
import com.travel.agent.entity.TripPhoto;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.TripPhotoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 行程照片：本地磁盘存储（默认 data/photos，可用 travel.photos.dir 覆盖）。
 * 库里只存元数据，原始文件名不外露；读取/删除都要先校验行程归属。
 */
@Service
@RequiredArgsConstructor
public class PhotoService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 允许的图片扩展名（有些客户端/系统不带 MIME 头，只能靠扩展名判断） */
    private static final Set<String> IMAGE_EXT = Set.of(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif");

    private final TripPhotoRepository repository;
    private final TripService tripService;
    private final PhotoStorage storage;

    /** 照片 + 它在磁盘上的位置 */
    public record StoredPhoto(TripPhoto photo, Path path) {}

    @Transactional
    public TripPhoto upload(Long tripId, Long userId, MultipartFile file) {
        Trip trip = tripService.getOwned(tripId, userId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的照片");
        }
        String declaredType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String ext = rawExtension(file.getOriginalFilename());
        boolean imageByType = declaredType.startsWith("image/");
        boolean imageByExt = ext != null && IMAGE_EXT.contains(ext);
        if (!imageByType && !imageByExt) {
            throw new BusinessException(400, "只支持图片文件（jpg/png/webp/heic 等）");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(400, "单张照片不能超过 10MB");
        }
        // 存储用扩展名与 MIME：优先客户端声明，其次按扩展名推断
        String storedExt = ext != null && IMAGE_EXT.contains(ext) ? ext : extensionFromMime(declaredType);
        String effectiveType = imageByType ? declaredType : mimeOf(storedExt);
        String storedName = UUID.randomUUID().toString().replace("-", "") + storedExt;
        storage.store(file, storedName);
        return repository.save(TripPhoto.builder()
                .tripId(trip.getId())
                .userId(userId)
                .storedName(storedName)
                .originalName(file.getOriginalFilename())
                .contentType(effectiveType)
                .sizeBytes(file.getSize())
                .build());
    }

    public List<TripPhoto> list(Long tripId, Long userId) {
        tripService.getOwned(tripId, userId);
        return repository.findByTripIdOrderByCreatedAtDesc(tripId);
    }

    /** 读取原图（先校验归属，再校验文件是否还在） */
    public StoredPhoto fileFor(Long photoId, Long userId) {
        return resolve(photoId, userId);
    }

    @Transactional
    public void delete(Long photoId, Long userId) {
        StoredPhoto stored = resolve(photoId, userId);
        repository.delete(stored.photo());
        storage.deleteQuietly(stored.photo().getStoredName());
    }

    private StoredPhoto resolve(Long photoId, Long userId) {
        TripPhoto photo = repository.findById(photoId)
                .orElseThrow(() -> new BusinessException(404, "照片不存在"));
        tripService.getOwned(photo.getTripId(), userId);
        if (!storage.exists(photo.getStoredName())) {
            throw new BusinessException(404, "照片文件不存在（可能已被清理）");
        }
        return new StoredPhoto(photo, storage.pathOf(photo.getStoredName()));
    }

    /** 原始扩展名；无法识别时返回 null（用于校验） */
    private static String rawExtension(String originalName) {
        if (originalName == null) {
            return null;
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return null;
        }
        String ext = originalName.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : null;
    }

    private static String extensionFromMime(String mime) {
        if (mime.contains("png")) return ".png";
        if (mime.contains("webp")) return ".webp";
        if (mime.contains("gif")) return ".gif";
        if (mime.contains("bmp")) return ".bmp";
        if (mime.contains("heic") || mime.contains("heif")) return ".heic";
        return ".jpg";
    }

    private static String mimeOf(String ext) {
        return switch (ext) {
            case ".png" -> "image/png";
            case ".webp" -> "image/webp";
            case ".gif" -> "image/gif";
            case ".bmp" -> "image/bmp";
            case ".heic", ".heif" -> "image/heic";
            default -> "image/jpeg";
        };
    }
}
