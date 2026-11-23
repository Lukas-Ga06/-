import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { BankOutlined, CarOutlined, CoffeeOutlined, EnvironmentOutlined } from '@ant-design/icons';
import type { ItineraryItem } from '../../types/trip';

/**
 * 行程项类型 → 地图标记图标（@ant-design/icons）。
 * 地图标记是命令式 DOM：用 react-dom/server 把同一个 Ant 图标渲染成 SVG，
 * 保证标记与界面其他位置使用同一套图标，不再受操作系统 Emoji 字体影响。
 */
const TYPE_ICON = {
  hotel: BankOutlined,
  attraction: EnvironmentOutlined,
  restaurant: CoffeeOutlined,
  transport: CarOutlined,
} as const;

/** 固定尺寸的 SVG：地图标记尺寸不随图标库版本变化 */
export const TYPE_ICON_NAME: Record<string, string> = {
  hotel: 'hotel',
  attraction: 'attraction',
  restaurant: 'restaurant',
  transport: 'transport',
};

function iconMarkup(itemType: string): string {
  const Icon = TYPE_ICON[itemType as keyof typeof TYPE_ICON] ?? TYPE_ICON.attraction;
  return renderToStaticMarkup(createElement(Icon, { 'aria-hidden': true }));
}

/**
 * 构建地图标记 DOM。
 *
 * 刻意使用 createElement + textContent，而不是 `innerHTML = \`...${item.title}...\``：
 * 行程标题来自用户输入或大模型生成，拼进 innerHTML 会被当作 HTML 解析，形成 XSS 注入点。
 * 标题一律作为文本节点写入；图标只来自上面固定的常量映射，不接收用户输入。
 */
export function buildMarkerElement(item: ItineraryItem, dayColor: string): HTMLDivElement {
  const el = document.createElement('div');
  el.className = 'm3d-marker';
  el.style.setProperty('--mc', dayColor);

  const ring = document.createElement('span');
  ring.className = 'm3d-ring';

  const dot = document.createElement('span');
  dot.className = 'm3d-dot';
  dot.setAttribute('aria-hidden', 'true');
  dot.dataset.icon = TYPE_ICON_NAME[item.itemType] ?? 'attraction';
  dot.innerHTML = iconMarkup(item.itemType);

  const label = document.createElement('span');
  label.className = 'm3d-label';
  label.textContent = item.title ?? '';

  el.append(ring, dot, label);
  el.title = item.description || item.title || '';
  return el;
}
