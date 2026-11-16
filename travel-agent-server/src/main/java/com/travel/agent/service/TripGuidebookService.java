package com.travel.agent.service;

import com.lowagie.text.pdf.BaseFont;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 行程路书导出：行程 + 每日安排 + 预算小结 → 打印友好的 HTML → PDF。
 * 中文渲染依赖 CJK 字体，探测顺序：classpath:fonts/cjk.*（可自行放置）→ Windows → Linux/Mac 常见字体；
 * 一个都找不到时抛业务异常并提示安装方式，避免导出乱码方块。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripGuidebookService {

    private final TripService tripService;
    private final ItineraryService itineraryService;
    private final ExpenseService expenseService;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** 一个候选字体：path 为文件路径或 classpath 相对路径，family 必须与 CSS font-family 一致 */
    private record FontCandidate(String path, String family, boolean classpath) { }

    public byte[] renderPdf(Long tripId, Long userId) {
        FontCandidate font = findCjkFont();
        String html = renderHtml(tripId, userId);
        try {
            ITextRenderer renderer = new ITextRenderer();
            ITextFontResolver resolver = renderer.getFontResolver();
            resolver.addFont(fontFileFor(font) + (font.path().endsWith(".ttc") ? ",0" : ""),
                    BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            log.info("路书 PDF 使用中文字体: {} ({})", font.family(), font.path());
            renderer.setDocumentFromString(html);
            renderer.layout();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("路书 PDF 生成失败 tripId={}", tripId, e);
            throw new BusinessException(500, "路书 PDF 生成失败，请稍后重试");
        }
    }

    /** 路书内容（HTML），PDF 与前端预览共用同一模板 */
    public String renderHtml(Long tripId, Long userId) {
        Trip trip = tripService.getOwned(tripId, userId);
        List<ItineraryItem> items = itineraryService.listEntities(trip.getId()).stream()
                .sorted(Comparator.comparingInt(ItineraryItem::getDayIndex)
                        .thenComparing(i -> i.getStartTime() == null ? LocalDateTime.MAX : i.getStartTime())
                        .thenComparing(ItineraryItem::getId))
                .toList();

        BigDecimal planned = items.stream()
                .map(ItineraryItem::getCost).filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = expenseService.totalOf(trip.getId());

        String body = new StringBuilder()
                .append(cover(trip))
                .append(overview(trip, items, planned, actual))
                .append(daysSection(trip, items))
                .toString();
        String generatedAt = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return TEMPLATE.formatted(findFontFamilyForCss(), body, generatedAt);
    }

    // ==================== HTML 分段 ====================

    private String cover(Trip trip) {
        String dates = trip.getStartDate() == null ? "日期待定"
                : DATE.format(trip.getStartDate()) + " ~ " + (trip.getEndDate() == null ? "待定" : DATE.format(trip.getEndDate()));
        return """
                <div class="cover">
                  <h1 class="cover-title">%s</h1>
                  <div class="cover-sub">%s · %d 天 · %s</div>
                </div>
                """.formatted(esc(orDefault(trip.getName(), "我的行程")),
                esc(orDefault(trip.getDestination(), "目的地待定")), trip.totalDays(), dates);
    }

    private String overview(Trip trip, List<ItineraryItem> items, BigDecimal planned, BigDecimal actual) {
        return """
                <table class="meta">
                  <tr><th>目的地</th><td>%s</td><th>状态</th><td>%s</td></tr>
                  <tr><th>行程预算</th><td>%s</td><th>计划成本</th><td>%s</td></tr>
                  <tr><th>实际支出</th><td>%s</td><th>安排数量</th><td>%d 项</td></tr>
                </table>
                """.formatted(
                esc(orDefault(trip.getDestination(), "待定")), esc(phaseLabel(trip)),
                money(trip.getBudget()), money(planned), money(actual), items.size());
    }

    private String daysSection(Trip trip, List<ItineraryItem> items) {
        if (items.isEmpty()) {
            return "<p class='empty'>暂无安排 —— 可在网页端让 AI 助手规划，之后再导出路书。</p>";
        }
        Map<Integer, List<ItineraryItem>> byDay = new LinkedHashMap<>();
        for (ItineraryItem item : items) {
            byDay.computeIfAbsent(item.getDayIndex(), k -> new ArrayList<>()).add(item);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, List<ItineraryItem>> entry : byDay.entrySet()) {
            int day = entry.getKey();
            String dayDate = trip.getStartDate() == null ? ""
                    : DATE.format(trip.getStartDate().plusDays(day - 1L)) + " · "
                      + trip.getStartDate().plusDays(day - 1L).getDayOfWeek()
                        .getDisplayName(TextStyle.FULL, Locale.CHINA);
            sb.append("<h2 class='day'>Day ").append(day)
                    .append(dayDate.isEmpty() ? "" : " <span class='day-date'>" + esc(dayDate) + "</span>")
                    .append("</h2><table class='items'><tr><th style='width:12%'>时间</th><th style='width:14%'>类型</th>")
                    .append("<th style='width:30%'>安排</th><th style='width:10%'>费用</th><th>说明</th></tr>");
            for (ItineraryItem item : entry.getValue()) {
                String time = item.getStartTime() == null ? "—" : TIME.format(item.getStartTime());
                sb.append("<tr><td>").append(time)
                        .append("</td><td><span class='badge badge-").append(esc(item.getItemType()))
                        .append("'>").append(typeLabel(item.getItemType())).append("</span></td><td>")
                        .append(esc(item.getTitle()))
                        .append("</td><td>").append(money(item.getCost()))
                        .append("</td><td class='desc'>").append(esc(orDefault(item.getDescription(), "")))
                        .append("</td></tr>");
            }
            sb.append("</table>");
        }
        return sb.toString();
    }

    private String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case ItineraryItem.TYPE_HOTEL -> "酒店";
            case ItineraryItem.TYPE_ATTRACTION -> "景点";
            case ItineraryItem.TYPE_RESTAURANT -> "美食";
            case ItineraryItem.TYPE_TRANSPORT -> "交通";
            default -> "其他";
        };
    }

    private String phaseLabel(Trip trip) {
        return switch (trip.phase(LocalDate.now())) {
            case "PAST" -> "已结束";
            case "ONGOING" -> "进行中";
            case "CANCELLED" -> "已取消";
            default -> "未开始";
        };
    }

    // ==================== 模板与工具 ====================

    private static final String TEMPLATE = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"/><style>
            @page { size: A4; margin: 16mm 15mm; }
            body { font-family: '%s', sans-serif; color: #1a1a1a; font-size: 11px; line-height: 1.6; }
            .cover { text-align: center; padding: 22px 10px 14px; border-bottom: 3px solid #2f6fed; margin-bottom: 14px; }
            .cover-title { font-size: 24px; margin: 0 0 6px; }
            .cover-sub { color: #555; font-size: 12px; }
            table.meta { width: 100%%; border-collapse: collapse; margin: 8px 0 14px; }
            table.meta th, table.meta td { border: 1px solid #d8dee9; padding: 6px 8px; text-align: left; }
            table.meta th { background: #f2f6ff; width: 16%%; }
            h2.day { font-size: 14px; margin: 16px 0 6px; padding: 5px 10px; background: #eef4ff; border-left: 4px solid #2f6fed; }
            .day-date { font-size: 10px; color: #666; font-weight: normal; }
            table.items { width: 100%%; border-collapse: collapse; }
            table.items th, table.items td { border: 1px solid #d8dee9; padding: 5px 7px; vertical-align: top; }
            table.items th { background: #f7f9fc; }
            .badge { font-size: 9px; padding: 1px 7px; border-radius: 8px; color: #fff; }
            .badge-attraction { background: #2f6fed; }
            .badge-restaurant { background: #e6752e; }
            .badge-hotel { background: #7c4dcc; }
            .badge-transport { background: #2e9e5b; }
            .desc { color: #444; }
            .empty { color: #666; }
            .footer { margin-top: 20px; color: #888; font-size: 9px; text-align: center; border-top: 1px solid #ddd; padding-top: 6px; }
            </style></head>
            <body>%s<div class="footer">由 AI 个人助理 · 行程路书生成于 %s</div></body>
            </html>
            """;

    // ==================== 中文字体探测 ====================

    /** CSS font-family：classpath 字体在渲染前才确定族名，这里只做占位（renderPdf 与 renderHtml 都会再走一次探测） */
    private String findFontFamilyForCss() {
        return findCjkFont().family();
    }

    private FontCandidate findCjkFont() {
        // 1. classpath（用户可在 resources/fonts 放置 cjk.ttf / cjk.ttc / cjk.otf，随包分发最稳）
        for (String name : List.of("fonts/cjk.ttf", "fonts/cjk.otf", "fonts/cjk.ttc")) {
            if (new ClassPathResource(name).exists()) {
                return new FontCandidate(name, classpathFontFamily(name), true);
            }
        }
        // 2. Windows（微软雅黑 / 宋体 / 黑体）
        for (FontCandidate c : List.of(
                new FontCandidate("C:/Windows/Fonts/msyh.ttc", "Microsoft YaHei", false),
                new FontCandidate("C:/Windows/Fonts/msyh.ttf", "Microsoft YaHei", false),
                new FontCandidate("C:/Windows/Fonts/simsun.ttc", "SimSun", false),
                new FontCandidate("C:/Windows/Fonts/simhei.ttf", "SimHei", false))) {
            if (new File(c.path()).isFile()) {
                return c;
            }
        }
        // 3. Linux（Noto CJK / 文泉驿）与 macOS
        for (FontCandidate c : List.of(
                new FontCandidate("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "Noto Sans CJK SC", false),
                new FontCandidate("/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc", "Noto Sans CJK SC", false),
                new FontCandidate("/usr/share/fonts/truetype/wqy/wqy-microhei.ttc", "WenQuanYi Micro Hei", false),
                new FontCandidate("/System/Library/Fonts/PingFang.ttc", "PingFang SC", false))) {
            if (new File(c.path()).isFile()) {
                return c;
            }
        }
        throw new BusinessException(500,
                "服务器未找到可用的中文字体，无法生成 PDF。"
                        + "可在后端 resources/fonts/ 放置 cjk.ttf，或在系统安装中文字体（如 Windows 微软雅黑 / Linux fonts-noto-cjk）");
    }

    /** classpath 字体族名用 AWT 读取（headless 下可用）；读不出时退回通用名 */
    private String classpathFontFamily(String classpath) {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            File tmp = File.createTempFile("cjk-font", classpath.endsWith(".ttc") ? ".ttc" : ".ttf");
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Font[] fonts = classpath.endsWith(".ttc") ? Font.createFonts(tmp) : new Font[]{Font.createFont(Font.TRUETYPE_FONT, tmp)};
            return fonts[0].getFamily(Locale.CHINA);
        } catch (Exception e) {
            log.warn("读取 classpath 字体族名失败，使用默认族名: {}", e.getMessage());
            return "CJK";
        }
    }

    private String fontFileFor(FontCandidate font) {
        if (!font.classpath()) {
            return font.path();
        }
        try (InputStream in = new ClassPathResource(font.path()).getInputStream()) {
            File tmp = File.createTempFile("cjk-font", font.path().substring(font.path().lastIndexOf('.')));
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return tmp.getAbsolutePath();
        } catch (Exception e) {
            throw new BusinessException(500, "读取中文字体失败: " + font.path());
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? "—" : "¥" + value.stripTrailingZeros().toPlainString();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
