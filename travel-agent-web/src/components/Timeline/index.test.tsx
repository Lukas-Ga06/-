// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
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
    item({
      id: 2,
      title: '豫园',
      startTime: '11:00',
      endTime: '12:30',
      latitude: 31.24,
      longitude: 121.48,
    }),
  ];
}

describe('Timeline · 选中态（uiStore.selectedElement 是唯一选中源）', () => {
  it('点卡片写 selectedElement；外部写入（例如地图标记点击）同样让对应行高亮', () => {
    render(<Timeline trip={null} items={twoItems()} focus={null} />);

    // 初始没有选中：两行都不高亮
    expect(document.getElementById('ti-1')?.className).not.toContain('selected');
    expect(document.getElementById('ti-2')?.className).not.toContain('selected');

    // 时间线自己写：行与卡片同时高亮
    const card = document.querySelector('#ti-2 .t-card') as HTMLElement;
    fireEvent.click(card);
    expect(useUIStore.getState().selectedElement).toEqual({
      type: 'attraction',
      id: 2,
      title: '豫园',
    });
    expect(document.getElementById('ti-2')?.className).toContain('selected');
    expect(card.className).toContain('selected');

    // 地图侧写同一个字段：时间线不需要额外通知就跟着高亮（没有第二套选中态）
    act(() => {
      useUIStore.getState().setSelectedElement({ type: 'attraction', id: 1, title: '外滩' });
    });
    expect(document.getElementById('ti-1')?.className).toContain('selected');
    expect(document.getElementById('ti-2')?.className).not.toContain('selected');
  });
});

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

describe('Timeline · P1 行程项状态', () => {
  it('条目显示状态徽标，状态按钮回传条目 id 与目标状态', () => {
    const onItemStatusChange = vi.fn();
    render(
      <Timeline
        trip={null}
        items={[
          item({ status: 'IN_PROGRESS' }),
          item({ id: 2, title: '豫园', startTime: '11:00' }),
        ]}
        focus={null}
        onItemStatusChange={onItemStatusChange}
      />,
    );

    expect(screen.getAllByTestId('item-status').map((node) => node.textContent)).toEqual([
      '进行中',
      '未开始',
    ]);
    // 进行中条目的卡片主动作是「完成」（顶部「下一项」也有同名按钮，取卡片里那个）
    const cardDone = screen
      .getAllByRole('button', { name: /完\s*成/ })
      .find((el) => el.closest('.t-card') != null) as HTMLButtonElement;
    fireEvent.click(cardDone);
    expect(onItemStatusChange).toHaveBeenCalledWith(1, 'COMPLETED');
  });
});

describe('Timeline · 下一项（行中第一信息，P1-04）', () => {
  it('顶部展示下一项的时间、标题与转场信息，并直接给出主动作', () => {
    const onItemStatusChange = vi.fn();
    render(
      <Timeline
        trip={null}
        items={twoItems()}
        focus={null}
        routeLegs={[leg()]}
        onItemStatusChange={onItemStatusChange}
      />,
    );

    const bar = screen.getByTestId('next-up');
    expect(bar.textContent).toContain('下一项');
    expect(bar.textContent).toContain('09:00');
    expect(bar.textContent).toContain('外滩');

    fireEvent.click(
      Array.from(bar.querySelectorAll('button')).find(
        (b) => b.textContent?.replace(/\s/g, '') === '开始',
      ) as Element,
    );
    expect(onItemStatusChange).toHaveBeenCalledWith(1, 'IN_PROGRESS');
  });

  it('进行中的条目优先成为「下一项」，主动作变成完成', () => {
    const onItemStatusChange = vi.fn();
    render(
      <Timeline
        trip={null}
        items={[item({ status: 'PENDING' }), item({ id: 2, title: '豫园', status: 'IN_PROGRESS' })]}
        focus={null}
        onItemStatusChange={onItemStatusChange}
      />,
    );

    const bar = screen.getByTestId('next-up');
    expect(bar.textContent).toContain('豫园');
    fireEvent.click(
      Array.from(bar.querySelectorAll('button')).find(
        (b) => b.textContent?.replace(/\s/g, '') === '完成',
      ) as Element,
    );
    expect(onItemStatusChange).toHaveBeenCalledWith(2, 'COMPLETED');
  });

  it('全部完成后不再显示「下一项」', () => {
    render(
      <Timeline
        trip={null}
        items={[item({ status: 'COMPLETED' }), item({ id: 2, title: '豫园', status: 'SKIPPED' })]}
        focus={null}
        onItemStatusChange={vi.fn()}
      />,
    );

    expect(screen.queryByTestId('next-up')).toBeNull();
  });
});

describe('Timeline · 待同步标记（§7.3 第 4 条）', () => {
  it('命中 pendingSyncIds 的条目在状态徽标旁显示「待同步」', () => {
    const { container } = render(
      <Timeline
        trip={null}
        items={[
          item({ id: 1, status: 'COMPLETED' }),
          item({ id: 2, title: '豫园', startTime: '11:00', status: 'PENDING' }),
        ]}
        focus={null}
        pendingSyncIds={new Set([1])}
      />,
    );

    const pendingTags = screen.getAllByTestId('pending-sync');
    expect(pendingTags).toHaveLength(1);
    expect(pendingTags[0].textContent).toContain('待同步');
    // 标记落在已完成的那一条上，而不是所有卡片
    expect(pendingTags[0].closest('.t-card')?.textContent).toContain('外滩');
    // 卡片上同时带 data-pending，便于样式区分「已确认」与「待同步」
    expect(container.querySelectorAll('.t-card[data-pending="true"]')).toHaveLength(1);
  });

  it('没有待同步时行为与改动前一致（不渲染标记）', () => {
    const { container } = render(
      <Timeline trip={null} items={twoItems()} focus={null} pendingSyncIds={new Set()} />,
    );

    expect(screen.queryByTestId('pending-sync')).toBeNull();
    expect(container.querySelectorAll('.t-card[data-pending="true"]')).toHaveLength(0);
  });
});

describe('Timeline · 行程卡片价格溯源（price 可选）', () => {
  const price = {
    amount: 520,
    currency: 'CNY',
    kind: 'reference',
    label: '参考价',
    source: 'local-estimate',
    sourceLabel: '本地估算',
    disclaimer: '非实时报价：未接入房态/库存，实际价格与可订状态以 OTA 页面为准',
    links: [
      {
        provider: 'ctrip',
        label: '去携程查价',
        url: 'https://hotels.ctrip.com/hotels/1.html',
        kind: 'price',
      },
      {
        provider: 'amap',
        label: '在地图查看',
        url: 'https://uri.amap.com/marker?position=1,2',
        kind: 'map',
      },
    ],
  };

  it('时间线内的卡片同样渲染「参考价」标签、来源小字与两个深链', () => {
    const { container } = render(
      <Timeline
        trip={null}
        items={[item({ cost: 520, price }), item({ id: 2, title: '豫园', startTime: '11:00' })]}
        focus={null}
      />,
    );

    expect(screen.getByTestId('price-tag').textContent).toBe('参考价');
    expect(screen.getByTestId('price-source').textContent).toContain('本地估算');
    expect(screen.getByTestId('price-disclaimer').textContent).toContain('非实时报价');
    expect(screen.getAllByTestId('price-link')).toHaveLength(2);

    // 第二条没有 price：只显示旧行为，不重复出现标签
    expect(container.querySelectorAll('[data-testid="price-tag"]')).toHaveLength(1);
  });

  it('price 缺失 / 为 null 时时间线行为与改动前一致（无标签、无链接、不抛错）', () => {
    const { container } = render(
      <Timeline
        trip={null}
        items={[
          item({ id: 1, cost: 520 }),
          item({ id: 2, title: '豫园', cost: null, price: null }),
        ]}
        focus={null}
      />,
    );

    expect(container.querySelectorAll('[data-testid="price-tag"]')).toHaveLength(0);
    expect(container.querySelectorAll('[data-testid="price-link"]')).toHaveLength(0);
    expect(container.textContent).toContain('¥520');
    expect(container.textContent).not.toContain('立即预订');
  });
});
