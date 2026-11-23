// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { buildMarkerElement } from './markerElement';
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
    expect(el.querySelector('.m3d-label')?.textContent).toBe(payload);
    expect(el.querySelector('.m3d-label')?.children).toHaveLength(0);
    expect(el.querySelector('.m3d-label')?.innerHTML).not.toContain('<img');
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
});
