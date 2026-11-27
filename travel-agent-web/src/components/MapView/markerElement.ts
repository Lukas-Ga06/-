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
 * 路线来源 → 标记上的短标签。口径与时间轴一致：只有 `amap` 是实测，其余一律估算，
 * 估算不得被显示成实时数据（与 Timeline 的 RouteLegRow 同一判定）。
 */
export function markerSourceTag(source: string): { text: string; cls: string; title: string } {
  return source === 'amap'
    ? { text: '实测', cls: 'leg-amap', title: '路线来源：amap（高德实测，可复核）' }
    : {
        text: '估算',
        cls: 'leg-estimated',
        title: '路线来源：estimated（按距离估算，可能受路况影响）',
      };
}

/**
 * 选中投影的唯一实现：类与 `data-selected` 一起改。
 * 样式与自动化读的是同一份状态，不会出现「看起来选中但断言读不到」。
 */
export function applyMarkerSelection(element: HTMLElement, selected: boolean): void {
  element.classList.toggle('m3d-selected', selected);
  element.dataset.selected = selected ? 'true' : 'false';
}

export interface MarkerOptions {
  /** 进入该点的上一段路线来源；没有可标注的路线段时省略 */
  source?: string | null;
  /** 首帧是否为选中项；之后的切换由 AMapProvider.select 改同一组类/属性 */
  selected?: boolean;
}

/**
 * 构建地图标记 DOM。
 *
 * 刻意使用 createElement + textContent，而不是 `innerHTML = \`...${item.title}...\``：
 * 行程标题来自用户输入或大模型生成，拼进 innerHTML 会被当作 HTML 解析，形成 XSS 注入点。
 * 标题一律作为文本节点写入；图标只来自上面固定的常量映射，不接收用户输入。
 */
export function buildMarkerElement(
  item: ItineraryItem,
  dayColor: string,
  options: MarkerOptions = {},
): HTMLDivElement {
  const el = document.createElement('div');
  el.className = 'm3d-marker';
  el.style.setProperty('--mc', dayColor);
  if (item.id != null) el.dataset.itemId = String(item.id);

  const ring = document.createElement('span');
  ring.className = 'm3d-ring';

  const dot = document.createElement('span');
  dot.className = 'm3d-dot';
  dot.setAttribute('aria-hidden', 'true');
  dot.dataset.icon = TYPE_ICON_NAME[item.itemType] ?? 'attraction';
  dot.innerHTML = iconMarkup(item.itemType);

  const label = document.createElement('span');
  label.className = 'm3d-label';
  const title = document.createElement('span');
  title.className = 'm3d-title';
  title.textContent = item.title ?? '';
  label.append(title);
  if (options.source) {
    const tag = markerSourceTag(options.source);
    const src = document.createElement('span');
    src.className = `m3d-src mini-tag ${tag.cls}`;
    src.dataset.source = options.source;
    src.title = tag.title;
    src.textContent = tag.text;
    label.append(src);
  }

  el.append(ring, dot, label);
  el.title = item.description || item.title || '';
  applyMarkerSelection(el, options.selected === true);
  return el;
}
