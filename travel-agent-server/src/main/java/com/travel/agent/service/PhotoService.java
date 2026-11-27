package com.travel.agent.service;

import com.travel.agent.entity.Trip;
import com.travel.agent.entity.TripPhoto;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.TripPhotoRepository;
import com.travel.agent.util.ImageSignatures;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 行程照片：本地磁盘存储（默认 data/photos，可用 travel.photos.dir 覆盖）。
 * 库里只存元数据，原始文件名不外露；读取/删除都要先校验行程归属。
 */
@Service
public class PhotoService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 允许的图片扩展名（有些客户端/系统不带 MIME 头，只能靠扩展名判断） */
    private static final Set<String> IMAGE_EXT = Set.of(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif");
    /**
     * 允许的 MIME 白名单。必须是枚举而不是 "image/" 前缀判断：
     * image/svg+xml 同样以 image/ 开头，但 SVG 可以内嵌脚本，存下来就成了存储型 XSS。
     */
    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif");

    private final TripPhotoRepository repository;
    private final TripService tripService;
    private final PhotoStore storage;
    private final int maxPhotosPerTrip;
    private final long maxBytesPerUser;

    public PhotoService(TripPhotoRepository repository,
                        TripService tripService,
                        PhotoStore storage,
                        @Value("${travel.photos.max-per-trip:200}") int maxPhotosPerTrip,
                        @Value("${travel.photos.max-bytes-per-user:524288000}") long maxBytesPerUser) {
        this.repository = repository;
        this.tripService = tripService;
        this.storage = storage;
        this.maxPhotosPerTrip = maxPhotosPerTrip;
        this.maxBytesPerUser = maxBytesPerUser;
    }

    /** 照片 + 原图资源（本地文件或对象存储流） */
    public record StoredPhoto(TripPhoto photo, org.springframework.core.io.Resource resource) {}

    @Transactional
    public TripPhoto upload(Long tripId, Long userId, MultipartFile file) {
        return upload(tripId, userId, file, TripPhoto.KIND_PHOTO);
    }

    /**
     * 上传照片并标记用途（V32）：{@code kind=INVOICE} 的图片会被发票报销流程取用。
     *
     * <p>用途与文件本身同等校验：非法值直接 400，不落库、不落盘——
     * 用途是报销流程的判据，脏值会让「取最新一张发票」取到不相干的图。
     */
    @Transactional
    public TripPhoto upload(Long tripId, Long userId, MultipartFile file, String kind) {
        String resolvedKind = normalizeKind(kind);
        Trip trip = tripService.getOwned(tripId, userId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的照片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(400, "单张照片不能超过 10MB");
        }
        if (repository.countByTripId(trip.getId()) >= maxPhotosPerTrip) {
            throw new BusinessException(400, "该行程照片已达上限（" + maxPhotosPerTrip + " 张），请先清理一些");
        }
        long usedBytes = repository.totalBytesOfUser(userId);
        if (usedBytes + file.getSize() > maxBytesPerUser) {
            throw new BusinessException(400, "照片存储空间已满（上限 "
                    + (maxBytesPerUser / 1024 / 1024) + "MB），请先删除一些照片");
        }

        String declaredType = normalizeMime(file.getContentType());
        String ext = rawExtension(file.getOriginalFilename());
        boolean allowedByType = ALLOWED_MIME.contains(declaredType);
        boolean allowedByExt = ext != null && IMAGE_EXT.contains(ext);
        if (!allowedByType && !allowedByExt) {
            throw new BusinessException(400, "只支持 jpg/png/webp/gif/bmp/heic 图片，不支持 svg 等矢量格式");
        }

        // 以文件头为准：声明成图片但内容不是图片（或声明成 svg）一律拒绝
        String detectedType = detectType(file);
        if (detectedType == null) {
            throw new BusinessException(400, "文件内容不是可识别的图片，请重新选择");
        }

        // 存储扩展名与响应类型都由服务端推导，不回显客户端声明
        String storedExt = ImageSignatures.extensionOf(detectedType);
        String storedName = UUID.randomUUID().toString().replace("-", "") + storedExt;

        // 先写元数据、再落盘：先落盘的话，插入失败会留下永远回收不到的孤儿文件；
        // 落盘失败则抛出异常回滚事务，并清理可能写了一半的文件。
        TripPhoto photo = repository.save(TripPhoto.builder()
                .tripId(trip.getId())
                .userId(userId)
                .storedName(storedName)
                .originalName(file.getOriginalFilename())
                .contentType(detectedType)
                .sizeBytes(file.getSize())
                .kind(resolvedKind)
                .build());
        try {
            storage.store(file, storedName);
        } catch (RuntimeException e) {
            storage.deleteQuietly(storedName);
            throw e;
        }
        return photo;
    }

    public List<TripPhoto> list(Long tripId, Long userId) {
        tripService.getOwned(tripId, userId);
        return repository.findByTripIdOrderByCreatedAtDesc(tripId);
    }

    /**
     * 该用户最新一张发票照片（V32）。没有上传过返回空——调用方必须如实说「没有发票照片」，
     * 不能退化成「取任意一张照片去识别」。
     */
    public Optional<TripPhoto> latestInvoice(Long userId) {
        return repository.findFirstByUserIdAndKindOrderByIdDesc(userId, TripPhoto.KIND_INVOICE);
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
        return new StoredPhoto(photo, storage.read(photo.getStoredName()));
    }

    /**
     * 用途归一化：null/空白视为 PHOTO（老客户端不带 kind）；只接受 PHOTO / INVOICE。
     *
     * <p>大小写不敏感地归一成大写常量，避免 "invoice" 这类写法在库里留下第二套取值，
     * 让「按 kind 取最新一张」的查询时灵时不灵。
     */
    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return TripPhoto.KIND_PHOTO;
        }
        String upper = kind.trim().toUpperCase(java.util.Locale.ROOT);
        if (!TripPhoto.KIND_PHOTO.equals(upper) && !TripPhoto.KIND_INVOICE.equals(upper)) {
            throw new BusinessException(400, "不支持的图片用途");
        }
        return upper;
    }

    private static String detectType(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return ImageSignatures.detect(in);
        } catch (IOException e) {
            throw new BusinessException(400, "无法读取上传文件，请重试");
        }
    }

    /** 去掉 charset 等参数并归一化别名，避免 "image/jpg"、"IMAGE/PNG; charset=utf-8" 之类绕过白名单 */
    private static String normalizeMime(String raw) {
        if (raw == null) {
            return "";
        }
        String mime = raw.split(";")[0].trim().toLowerCase();
        return "image/jpg".equals(mime) ? "image/jpeg" : mime;
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

}
