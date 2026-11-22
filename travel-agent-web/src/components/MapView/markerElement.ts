import type { ItineraryItem } from '../../types/trip';

/** 行程项类型 → 地图标记图标 */
export const TYPE_ICON: Record<string, string> = {
  hotel: '🏨',
  attraction: '📍',
  restaurant: '🍽',
  transport: '🚗',
};

/**
 * 构建地图标记 DOM。
 *
 * 刻意使用 createElement + textContent，而不是 `innerHTML = \`...${item.title}...\``：
 * 行程标题来自用户输入或大模型生成，拼进 innerHTML 会被当作 HTML 解析，形成 XSS 注入点。
 * 标题一律作为文本节点写入，标签自己也从 data 属性读取（不改动引入路径）。
 */
export function buildMarkerElement(item: ItineraryItem, dayColor: string): HTMLDivElement {
  const el = document.createElement('div');
  el.className = 'm3d-marker';
  el.style.setProperty('--mc', dayColor);

  const ring = document.createElement('span');
  ring.className = 'm3d-ring';

  const dot = document.createElement('span');
  dot.className = 'm3d-dot';
  dot.textContent = TYPE_ICON[item.itemType] ?? '📍';

  const label = document.createElement('span');
  label.className = 'm3d-label';
  label.textContent = item.title ?? '';

  el.append(ring, dot, label);
  el.title = item.description || item.title || '';
  return el;
}
