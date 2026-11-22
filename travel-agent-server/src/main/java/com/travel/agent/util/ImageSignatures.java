package com.travel.agent.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 图片文件头识别。
 *
 * 上传时类型判定必须以文件内容为准：只看客户端声明的 MIME 会被绕过，
 * 例如把 SVG（可内嵌 &lt;script&gt;）声明成 image/svg+xml 即可通过 "image/" 前缀校验，
 * 之后原图接口又会按这个类型响应，直接打开链接就会在站点源上执行脚本。
 *
 * 这里只认可白名单内的栅格格式，无法识别一律返回 null（由调用方拒绝）。
 */
public final class ImageSignatures {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String GIF = "image/gif";
    public static final String BMP = "image/bmp";
    public static final String WEBP = "image/webp";
    public static final String HEIC = "image/heic";

    /** HEIF 家族的 ftyp brand 标识 */
    private static final Set<String> HEIF_BRANDS = Set.of(
            "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1");

    private ImageSignatures() {
    }

    /** 读取文件头并返回服务端认可的类型；无法识别返回 null */
    public static String detect(InputStream in) throws IOException {
        return detect(in.readNBytes(32));
    }

    public static String detect(byte[] head) {
        if (head == null || head.length < 4) {
            return null;
        }
        if (startsWith(head, 0, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (startsWith(head, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (startsWith(head, 0, 'G', 'I', 'F', '8')) {
            return GIF;
        }
        if (startsWith(head, 0, 'B', 'M')) {
            return BMP;
        }
        if (startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P')) {
            return WEBP;
        }
        if (head.length >= 12 && startsWith(head, 4, 'f', 't', 'y', 'p')
                && HEIF_BRANDS.contains(ascii(head, 8, 4))) {
            return HEIC;
        }
        return null;
    }

    /** 类型 → 存储扩展名（存储名由服务端生成，不采用客户端文件名） */
    public static String extensionOf(String mime) {
        return switch (mime == null ? "" : mime) {
            case PNG -> ".png";
            case GIF -> ".gif";
            case BMP -> ".bmp";
            case WEBP -> ".webp";
            case HEIC -> ".heic";
            default -> ".jpg";
        };
    }

    private static boolean startsWith(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) (data[offset + i] & 0xFF));
        }
        return sb.toString();
    }
}
