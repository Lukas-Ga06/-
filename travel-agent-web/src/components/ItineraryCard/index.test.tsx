// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import ItineraryCard from './index';
import type { ItineraryItem, ItineraryPrice } from '../../types/trip';

afterEach(cleanup);

function item(patch: Partial<ItineraryItem> = {}): ItineraryItem {
  return {
    id: 1,
    dayIndex: 1,
    itemType: 'hotel',
    title: '外滩茂悦大酒店',
    startTime: '15:00',
    endTime: '15:30',
    latitude: 31.24,
    longitude: 121.49,
    cost: 520,
    ...patch,
  };
}

/** 与后端 task-7 锁定的契约逐字段一致的 fixture（不伪造接口响应，只喂组件） */
const CONTRACT_PRICE: ItineraryPrice = {
  amount: 520.0,
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
      url: 'https://hotels.ctrip.com/hotels/123.html',
      kind: 'price',
    },
    {
      provider: 'amap',
      label: '在地图查看',
      url: 'https://uri.amap.com/marker?position=121.49,31.24',
      kind: 'map',
    },
  ],
};

/** 卡片上「我方文案」的全文：去掉后端免责声明节点后的 textContent */
function uiOwnCopy(container: HTMLElement): string {
  const clone = container.cloneNode(true) as HTMLElement;
  clone.querySelectorAll('[data-testid="price-disclaimer"]').forEach((node) => node.remove());
  return clone.textContent ?? '';
}

const FORBIDDEN_WORDS = ['实时', '可订', '有房', '立即预订'];

describe('ItineraryCard · P1 执行状态与操作', () => {
  it('已完成条目显示完成徽标与完成时间，并有明显视觉标记', () => {
    const { container } = render(
      <ItineraryCard
        item={item({
          status: 'COMPLETED',
          completedAt: '2026-05-01T10:30:00',
          visitedAt: '2026-05-01T10:30:00',
        })}
      />,
    );

    expect(screen.getByTestId('item-status').textContent).toBe('已完成');
    expect(container.querySelector('.t-card')?.className).toContain('tc-done');
    expect(container.textContent).toContain('完成于');
    expect(container.textContent).toContain('05-01 10:30');
  });

  it('未开始条目只有一个一级主动作「开始」，其余动作收进更多菜单', async () => {
    const onStatusChange = vi.fn();
    render(<ItineraryCard item={item({ status: 'PENDING' })} onStatusChange={onStatusChange} />);

    expect(screen.getByTestId('item-status').textContent).toBe('未开始');
    // 一级只剩一个主动作：不再有开始/完成/跳过/重置四个同权按钮
    expect(screen.getByRole('button', { name: /开\s*始/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /跳\s*过/ })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /开\s*始/ }));
    expect(onStatusChange).toHaveBeenLastCalledWith('IN_PROGRESS');

    // 其余动作在菜单里
    fireEvent.click(screen.getByRole('button', { name: '更多安排操作' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '直接完成' }));
    expect(onStatusChange).toHaveBeenLastCalledWith('COMPLETED');

    fireEvent.click(screen.getByRole('button', { name: '更多安排操作' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '跳过' }));
    expect(onStatusChange).toHaveBeenLastCalledWith('SKIPPED');
  });

  it('已跳过条目的主动作是重置，直接从菜单可回到未开始', () => {
    const onStatusChange = vi.fn();
    const { container } = render(
      <ItineraryCard item={item({ status: 'SKIPPED' })} onStatusChange={onStatusChange} />,
    );

    expect(screen.getByTestId('item-status').textContent).toBe('已跳过');
    expect(container.querySelector('.t-card')?.className).toContain('tc-skipped');
    fireEvent.click(screen.getByRole('button', { name: /重\s*置/ }));
    expect(onStatusChange).toHaveBeenLastCalledWith('PENDING');
  });

  it('进行中条目的主动作是「完成」，重置被收进菜单', async () => {
    const onStatusChange = vi.fn();
    render(
      <ItineraryCard item={item({ status: 'IN_PROGRESS' })} onStatusChange={onStatusChange} />,
    );

    fireEvent.click(screen.getByRole('button', { name: /完\s*成/ }));
    expect(onStatusChange).toHaveBeenLastCalledWith('COMPLETED');

    fireEvent.click(screen.getByRole('button', { name: '更多安排操作' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '重置' }));
    expect(onStatusChange).toHaveBeenLastCalledWith('PENDING');
  });
});

describe('ItineraryCard · 价格参考价与溯源展示', () => {
  it('有 price：金额 + 「参考价」标签 + 来源 + 免责声明 + 查价/地图深链', () => {
    const { container } = render(<ItineraryCard item={item({ price: CONTRACT_PRICE })} />);

    // 金额与「参考价」标签
    expect(screen.getByText('¥520')).toBeTruthy();
    expect(screen.getByTestId('price-tag').textContent).toBe('参考价');

    // 来源与免责声明必须可见（小字行，不是仅 hover 才有）
    expect(screen.getByTestId('price-source').textContent).toContain('本地估算');
    expect(screen.getByTestId('price-disclaimer').textContent).toBe(CONTRACT_PRICE.disclaimer);

    // 两个深链：按 kind 排序（price → map），文案固定，外链安全属性齐全
    const links = screen.getAllByTestId('price-link');
    expect(links).toHaveLength(2);

    expect(links[0].getAttribute('data-kind')).toBe('price');
    expect(links[0].textContent).toBe('去携程查价');
    expect(links[0].getAttribute('href')).toBe('https://hotels.ctrip.com/hotels/123.html');
    expect(links[0].getAttribute('target')).toBe('_blank');
    expect(links[0].getAttribute('rel')).toBe('noopener noreferrer');

    expect(links[1].getAttribute('data-kind')).toBe('map');
    expect(links[1].textContent).toBe('在地图查看');
    expect(links[1].getAttribute('href')).toBe('https://uri.amap.com/marker?position=121.49,31.24');
    expect(links[1].getAttribute('target')).toBe('_blank');
    expect(links[1].getAttribute('rel')).toBe('noopener noreferrer');

    // 金额只出现一次（不重复渲染 cost 与 price.amount）
    expect(container.querySelectorAll('.tc-cost')).toHaveLength(1);
  });

  it('price: null 回退为纯金额，不抛错、不渲染标签/链接', () => {
    expect(() => render(<ItineraryCard item={item({ price: null })} />)).not.toThrow();

    expect(screen.getByText('¥520')).toBeTruthy();
    expect(screen.queryByTestId('price-tag')).toBeNull();
    expect(screen.queryByTestId('price-source')).toBeNull();
    expect(screen.queryByTestId('price-disclaimer')).toBeNull();
    expect(screen.queryAllByTestId('price-link')).toHaveLength(0);
    expect(document.querySelectorAll('a')).toHaveLength(0);
  });

  it('旧后端完全没有 price 字段：与改动前一致（只显示金额 / 免费 / 无金额）', () => {
    const { rerender } = render(<ItineraryCard item={item()} />);
    expect(screen.getByText('¥520')).toBeTruthy();
    expect(screen.queryByTestId('price-tag')).toBeNull();
    expect(screen.queryAllByTestId('price-link')).toHaveLength(0);

    // cost=0（后端会因此给 price:null）沿用「免费」
    rerender(<ItineraryCard item={item({ cost: 0, price: null })} />);
    expect(screen.getByText('免费')).toBeTruthy();
    expect(document.querySelectorAll('.tc-cost.free')).toHaveLength(1);

    // cost 缺失：不显示任何金额
    rerender(<ItineraryCard item={item({ cost: null })} />);
    expect(document.querySelectorAll('.tc-cost')).toHaveLength(0);
    expect(screen.queryByTestId('price-tag')).toBeNull();
  });

  it('红线：我方文案不含「实时 / 可订 / 有房 / 立即预订」，后端给的标签也兜底成「参考价」', () => {
    const { container } = render(
      <ItineraryCard
        item={item({
          price: {
            // 故意喂带承诺字样的 label / 链接文案，验证展示层不采信
            amount: 520,
            currency: 'CNY',
            label: '实时可订价',
            sourceLabel: '实时房价',
            disclaimer: CONTRACT_PRICE.disclaimer,
            links: [
              {
                provider: 'ctrip',
                label: '立即预订',
                url: 'https://hotels.ctrip.com/x',
                kind: 'price',
              },
              { provider: 'amap', label: '有房地图', url: 'https://uri.amap.com/x', kind: 'map' },
            ],
          },
        })}
      />,
    );

    const ownCopy = uiOwnCopy(container);
    for (const word of FORBIDDEN_WORDS) {
      expect(ownCopy).not.toContain(word);
    }

    // 被兜底成固定文案
    expect(screen.getByTestId('price-tag').textContent).toBe('参考价');
    expect(screen.getByTestId('price-source').textContent).toContain('来源未标注');
    expect(screen.getAllByTestId('price-link')[0].textContent).toBe('去携程查价');
    expect(screen.getAllByTestId('price-link')[1].textContent).toBe('在地图查看');

    // 唯一允许出现这些字样的地方是后端免责声明本身（否定表述「非实时」），且原样展示
    expect(screen.getByTestId('price-disclaimer').textContent).toBe(CONTRACT_PRICE.disclaimer);
    expect(CONTRACT_PRICE.disclaimer).toContain('非实时报价');
  });

  it('links 为空 / 地址非法 / 只有 price 没有 amount：不渲染空链接和空金额', () => {
    const { rerender } = render(
      <ItineraryCard item={item({ price: { ...CONTRACT_PRICE, links: [] } })} />,
    );
    expect(screen.queryAllByTestId('price-link')).toHaveLength(0);
    expect(document.querySelectorAll('a')).toHaveLength(0);
    // 其它溯源信息照常展示
    expect(screen.getByTestId('price-tag')).toBeTruthy();
    expect(screen.getByTestId('price-disclaimer')).toBeTruthy();

    // 非 http(s) 地址一律不渲染成可点链接
    rerender(
      <ItineraryCard
        item={item({
          price: {
            ...CONTRACT_PRICE,
            links: [
              { provider: 'x', label: '去携程查价', url: 'javascript:alert(1)', kind: 'price' },
              { provider: 'y', label: '在地图查看', url: '', kind: 'map' },
              { provider: 'z', label: '无地址', kind: 'map' },
            ],
          },
        })}
      />,
    );
    expect(screen.queryAllByTestId('price-link')).toHaveLength(0);
    expect(document.querySelectorAll('a')).toHaveLength(0);

    // price 对象存在但 amount 缺失：不显示金额、不加「参考价」标签，只展示溯源信息
    rerender(
      <ItineraryCard item={item({ cost: null, price: { ...CONTRACT_PRICE, amount: null } })} />,
    );
    expect(document.querySelectorAll('.tc-cost')).toHaveLength(0);
    expect(screen.queryByTestId('price-tag')).toBeNull();
    expect(screen.getAllByTestId('price-link')).toHaveLength(2);
  });

  it('金额格式化：非整数保留两位、非 CNY 带币种码', () => {
    const { rerender } = render(
      <ItineraryCard item={item({ price: { ...CONTRACT_PRICE, amount: 99.5 } })} />,
    );
    expect(screen.getByText('¥99.50')).toBeTruthy();

    rerender(
      <ItineraryCard
        item={item({ price: { ...CONTRACT_PRICE, amount: 12.34, currency: 'USD' } })}
      />,
    );
    expect(screen.getByText('USD 12.34')).toBeTruthy();

    // currency 缺失按 CNY 处理
    rerender(
      <ItineraryCard item={item({ price: { ...CONTRACT_PRICE, amount: 66, currency: null } })} />,
    );
    expect(screen.getByText('¥66')).toBeTruthy();
  });

  it('点击深链不触发行程条目选中（只跳外链）', () => {
    const onClick = vi.fn();
    render(<ItineraryCard item={item({ price: CONTRACT_PRICE })} onClick={onClick} />);

    fireEvent.click(screen.getAllByTestId('price-link')[0]);
    expect(onClick).not.toHaveBeenCalled();

    // 卡片其它区域仍可点击选中
    fireEvent.click(screen.getByText('外滩茂悦大酒店'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
