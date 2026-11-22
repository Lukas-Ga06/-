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

  it('保留类型图标、主题色与 tooltip', () => {
    const el = buildMarkerElement(item('外滩', 'attraction'), '#34c759');

    expect(el.className).toBe('m3d-marker');
    expect(el.style.getPropertyValue('--mc')).toBe('#34c759');
    expect(el.querySelector('.m3d-dot')?.textContent).toBe('📍');
    expect(el.querySelector('.m3d-label')?.textContent).toBe('外滩');
    expect(el.title).toBe('外滩');
  });

  it('未知类型回退到默认图标', () => {
    const el = buildMarkerElement(item('某地', 'unknown-type'), '#ff9500');
    expect(el.querySelector('.m3d-dot')?.textContent).toBe('📍');
  });
});
