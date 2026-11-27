import dayjs from 'dayjs';
import type { RouteLeg } from '../../types/trip';

/** 距离保留一位小数：4.20 与 4.2 显示一致，整数不带多余的 .0 */
function formatKm(km: number): string {
  const rounded = Math.round(km * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

/**
 * 时间展示：既接受后端 ISO（2026-05-01T09:00:00），也接受表单侧的裸 HH:mm。
 * 解析不出来就返回空串，绝不把「Invalid Date」渲染到时间轴和「下一项」上。
 */
export function formatClock(value?: string | null): string {
  if (!value) return '';
  const trimmed = value.trim();
  const bare = /^(\d{1,2}):(\d{2})/.exec(trimmed);
  if (bare) return `${bare[1].padStart(2, '0')}:${bare[2]}`;
  const parsed = dayjs(trimmed);
  return parsed.isValid() ? parsed.format('HH:mm') : '';
}

/**
 * 转场行单行文案：`4.2 km · 驾车 12 分钟 / 步行 56 分钟 · 约 ¥22`。
 * 时长为 0/缺失的那一段不显示；taxiCost 恒为估算，保留「约」字，不显示成实时报价。
 */
export function routeLegText(leg: RouteLeg): string {
  const parts: string[] = [];
  if (leg.distanceKm != null && leg.distanceKm > 0) parts.push(`${formatKm(leg.distanceKm)} km`);
  const durations: string[] = [];
  if (leg.driveMinutes != null && leg.driveMinutes > 0)
    durations.push(`驾车 ${leg.driveMinutes} 分钟`);
  if (leg.walkMinutes != null && leg.walkMinutes > 0)
    durations.push(`步行 ${leg.walkMinutes} 分钟`);
  if (durations.length > 0) parts.push(durations.join(' / '));
  if (leg.taxiCost != null && leg.taxiCost > 0) parts.push(`约 ¥${Math.round(leg.taxiCost)}`);
  return parts.join(' · ');
}
