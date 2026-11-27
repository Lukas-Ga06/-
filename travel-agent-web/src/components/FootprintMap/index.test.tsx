// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import FootprintMap from './index';

/**
 * 足迹地图引擎失败（P1-E）：必须显示 failed + 重试，
 * 不能把「引擎没加载出来」渲染成「没有足迹」。
 */
const loader = vi.hoisted(() => ({ loadMaplibre: vi.fn() }));

vi.mock('./maplibreLoader', () => ({ loadMaplibre: loader.loadMaplibre }));

afterEach(() => {
  cleanup();
  loader.loadMaplibre.mockReset();
});

describe('FootprintMap · 引擎失败态', () => {
  it('引擎加载失败时显示 failed 档与重试，而不是空地图', async () => {
    loader.loadMaplibre.mockRejectedValue(new Error('引擎下载失败'));

    render(<FootprintMap points={[]} />);

    const box = await screen.findByTestId('footprint-map-error');
    expect(box.querySelector('[data-status="failed"]')).toBeTruthy();
    expect(box.textContent).toContain('地图暂时不可用');
    expect(box.textContent).toContain('足迹列表与行程时间轴仍可正常查看');
    expect(screen.getByTestId('footprint-map-retry')).toBeTruthy();
  });

  it('失败时把「地图不可用」上报给父级（父级据此区分空数据）', async () => {
    loader.loadMaplibre.mockRejectedValue(new Error('引擎下载失败'));
    const onErrorChange = vi.fn();

    render(<FootprintMap points={[]} onErrorChange={onErrorChange} />);

    await screen.findByTestId('footprint-map-error');
    expect(onErrorChange).toHaveBeenCalledWith(true);
  });
});
