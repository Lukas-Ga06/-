// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { applyMarkerSelection, buildMarkerElement, markerSourceTag } from './markerElement';
import type { ItineraryItem } from '../../types/trip';

function item(title: string, itemType = 'attraction'): ItineraryItem {
  return {
    id: 1,
    dayIndex: 1,
    itemType,
    title,
    startTime: null,
    endTime: null,
    latitude: 31.23,
    longitude: 121.47,
    cost: null,
  };
}

describe('buildMarkerElement', () => {
  it('标题只作为文本渲染，不被当作 HTML 解析', () => {
    const payload = '<img src=x onerror="window.__xss=1">';
    const el = buildMarkerElement(item(payload), '#007aff');

    expect(el.querySelector('img')).toBeNull();
    const label = el.querySelector('.m3d-label');
    expect(label?.textContent).toBe(payload);
    expect(label?.querySelector('img')).toBeNull();
    expect(label?.innerHTML).not.toContain('<img');
    expect(el.querySelector('.m3d-title')?.textContent).toBe(payload);
  });

  it('类型图标用 Ant Design SVG，而不是 Emoji 字体', () => {
    const el = buildMarkerElement(item('外滩', 'attraction'), '#34c759');

    expect(el.className).toBe('m3d-marker');
    expect(el.style.getPropertyValue('--mc')).toBe('#34c759');
    const dot = el.querySelector('.m3d-dot') as HTMLElement;
    expect(dot.querySelector('svg')).toBeTruthy();
    expect(dot.textContent).toBe('');
    expect(dot.getAttribute('aria-hidden')).toBe('true');
    expect(dot.dataset.icon).toBe('attraction');
    expect(el.querySelector('.m3d-label')?.textContent).toBe('外滩');
    expect(el.title).toBe('外滩');
  });

  it('未知类型回退到默认的定位图标', () => {
    const el = buildMarkerElement(item('某地', 'unknown-type'), '#ff9500');
    const dot = el.querySelector('.m3d-dot') as HTMLElement;
    expect(dot.dataset.icon).toBe('attraction');
    expect(dot.querySelector('svg')).toBeTruthy();
  });

  it('带上 itemId 与选中态：选中是类 + data-selected 双写，可被样式与自动化同时读到', () => {
    const el = buildMarkerElement(item('外滩'), '#007aff', { selected: true });

    expect(el.dataset.itemId).toBe('1');
    expect(el.dataset.selected).toBe('true');
    expect(el.classList.contains('m3d-selected')).toBe(true);

    applyMarkerSelection(el, false);
    expect(el.dataset.selected).toBe('false');
    expect(el.classList.contains('m3d-selected')).toBe(false);
  });

  it('标记自带路线来源标签：amap 显示实测，其余一律估算（不冒充实测）', () => {
    const measured = buildMarkerElement(item('外滩'), '#007aff', { source: 'amap' });
    const measuredTag = measured.querySelector('.m3d-src') as HTMLElement;
    expect(measuredTag.dataset.source).toBe('amap');
    expect(measuredTag.textContent).toBe('实测');
    expect(measuredTag.classList.contains('leg-amap')).toBe(true);

    const estimated = buildMarkerElement(item('豫园'), '#007aff', { source: 'estimated' });
    const estimatedTag = estimated.querySelector('.m3d-src') as HTMLElement;
    expect(estimatedTag.dataset.source).toBe('estimated');
    expect(estimatedTag.textContent).toBe('估算');
    expect(estimatedTag.classList.contains('leg-estimated')).toBe(true);
    // 来源不明的一律按估算展示，与时间轴同一判定
    expect(buildMarkerElement(item('某地'), '#007aff', { source: 'custom' }).textContent).toContain(
      '估算',
    );

    // 没有可标注的路线段时不硬塞标签
    expect(buildMarkerElement(item('某地'), '#007aff').querySelector('.m3d-src')).toBeNull();
    expect(buildMarkerElement(item('某地'), '#007aff', { source: null }).textContent).toBe('某地');
  });

  it('来源标签的悬停说明区分「实测可复核」与「按距离估算」', () => {
    expect(markerSourceTag('amap').title).toContain('高德实测');
    expect(markerSourceTag('estimated').title).toContain('按距离估算');
    expect(markerSourceTag('estimated').text).not.toBe(markerSourceTag('amap').text);
  });
});
