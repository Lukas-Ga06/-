// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import Timeline from './index';
import type { ItineraryItem, RouteLeg } from '../../types/trip';
import { useUIStore } from '../../store/uiStore';

afterEach(cleanup);

beforeEach(() => {
  useUIStore.setState({ selectedDay: 1, selectedElement: null });
});

function item(patch: Partial<ItineraryItem> = {}): ItineraryItem {
  return {
    id: 1,
    dayIndex: 1,
    itemType: 'attraction',
    title: '外滩',
    startTime: '09:00',
    endTime: '10:00',
    latitude: 31.23,
    longitude: 121.47,
    cost: null,
    ...patch,
  };
}

function leg(patch: Partial<RouteLeg> = {}): RouteLeg {
  return {
    dayIndex: 1,
    sequence: 1,
    fromItemId: 1,
    toItemId: 2,
    fromTitle: '外滩',
    toTitle: '豫园',
    fromLatitude: 31.23,
    fromLongitude: 121.47,
    toLatitude: 31.24,
    toLongitude: 121.48,
    distanceKm: 4.2,
    walkMinutes: 56,
    driveMinutes: 12,
    taxiCost: 22,
    source: 'estimated',
    ...patch,
  };
}

/** 同一天两条相邻安排：外滩(1) → 豫园(2) */
function twoItems(): ItineraryItem[] {
  return [
    item(),
    item({ id: 2, title: '豫园', startTime: '11:00', endTime: '12:30', latitude: 31.24, longitude: 121.48 }),
  ];
}

describe('Timeline · 转场行（FR-07 route-legs）', () => {
  it('同一天相邻两条安排之间插入转场行：距离 / 驾车 / 步行 / 打车估算 + 估算徽标', () => {
    const { container } = render(
      <Timeline trip={null} items={twoItems()} focus={null} routeLegs={[leg()]} />,
    );

    const rows = screen.getAllByTestId('route-leg');
    expect(rows).toHaveLength(1);
    const row = rows[0];
    expect(row.getAttribute('data-source')).toBe('estimated');
    expect(row.textContent).toContain('4.2 km');
    expect(row.textContent).toContain('驾车 12 分钟');
    expect(row.textContent).toContain('步行 56 分钟');
    expect(row.textContent).toContain('约 ¥22');
    // 估算不得显示成实时数据
    expect(row.textContent).toContain('估算');
    expect(row.textContent).not.toContain('实测');

    // 位置：外滩 → 转场行 → 豫园（Fragment 不产生额外 DOM 节点）
    const children = Array.from(container.querySelectorAll('.tl-scroll > *'));
    expect(children).toHaveLength(3);
    expect(children[1].getAttribute('data-testid')).toBe('route-leg');
    expect(children[0].textContent).toContain('外滩');
    expect(children[2].textContent).toContain('豫园');
  });

  it('转场行不参与选中、不可点击（没有 t-row / 行程卡片结构）', () => {
    render(<Timeline trip={null} items={twoItems()} focus={null} routeLegs={[leg()]} />);

    const row = screen.getByTestId('route-leg');
    expect(row.className).not.toContain('t-row');
    expect(row.querySelector('.t-card')).toBeNull();
    expect(row.className).not.toContain('selected');
  });

  it('无 legs / 匹配不到相邻两条安排时不渲染转场行', () => {
    const { rerender } = render(<Timeline trip={null} items={twoItems()} focus={null} />);
    expect(screen.queryByTestId('route-leg')).toBeNull();

    rerender(<Timeline trip={null} items={twoItems()} focus={null} routeLegs={[]} />);
    expect(screen.queryByTestId('route-leg')).toBeNull();

    // 配对不上（fromItemId 不匹配）或换了另一天的段，都不渲染
    rerender(
      <Timeline
        trip={null}
        items={twoItems()}
        focus={null}
        routeLegs={[leg({ fromItemId: 99 }), leg({ dayIndex: 2, fromItemId: 1, toItemId: 2 })]}
      />,
    );
    expect(screen.queryByTestId('route-leg')).toBeNull();
  });

  it('source=amap 显示「实测」；时长为 0 与价格为 0 的分段不显示', () => {
    render(
      <Timeline
        trip={null}
        items={twoItems()}
        focus={null}
        routeLegs={[leg({ source: 'amap', taxiCost: 0, driveMinutes: 0 })]}
      />,
    );

    const row = screen.getByTestId('route-leg');
    expect(row.getAttribute('data-source')).toBe('amap');
    expect(row.textContent).toContain('实测');
    expect(row.textContent).not.toContain('估算');
    expect(row.textContent).not.toContain('约 ¥');
    expect(row.textContent).not.toContain('驾车');
    // 其余可用信息照常展示
    expect(row.textContent).toContain('步行 56 分钟');
    expect(row.textContent).toContain('4.2 km');
  });
});
