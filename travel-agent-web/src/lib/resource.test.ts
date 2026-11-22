import { describe, expect, it } from 'vitest';
import {
  canRetry,
  errorResource,
  loadingResource,
  offlineStaleResource,
  resourceData,
  successOrEmpty,
  type ResourceState,
} from './resource';

describe('资源状态模型（P0-04）', () => {
  it('空数组是 empty，不是 loading 也不是 error', () => {
    const state = successOrEmpty<string[]>([]);
    expect(state.status).toBe('empty');
    expect(resourceData(state)).toBeUndefined();
  });

  it('非空数据是 success，并可由 resourceData 取出', () => {
    const state = successOrEmpty(['上海'], (v) => v.length === 0);
    expect(state.status).toBe('success');
    expect(resourceData(state)).toEqual(['上海']);
  });

  it('对象默认不判空：只有 null/undefined 才算空', () => {
    expect(successOrEmpty({ total: 0 }).status).toBe('success');
    expect(successOrEmpty(null).status).toBe('empty');
  });

  it('失败状态保留原因并可重试，且默认带不上前一份数据', () => {
    const state = errorResource<number[]>('500 内部错误');
    expect(state).toMatchObject({ status: 'error', retryable: true });
    expect(canRetry(state)).toBe(true);
    expect(resourceData(state)).toBeUndefined();
  });

  it('不可重试的错误不展示重试入口', () => {
    const state = errorResource<number[]>('方案不存在', { retryable: false });
    expect(canRetry(state)).toBe(false);
  });

  it('失败时保留上一份数据，用于「旧内容 + 错误提示」而不是清空页面', () => {
    const state = errorResource<number[]>('网络错误', { previous: [1, 2] });
    expect(resourceData(state)).toEqual([1, 2]);
    expect(state.status).toBe('error');
  });

  it('加载中保留上一份数据，避免整页闪成骨架', () => {
    const state = loadingResource([1]);
    expect(resourceData(state)).toEqual([1]);
  });

  it('离线缓存状态带缓存时间，数据仍然只读可渲染', () => {
    const state = offlineStaleResource(['杭州'], new Date('2026-11-20T10:00:00Z'));
    expect(state.status).toBe('offline-stale');
    expect(state.status === 'offline-stale' && state.cachedAt).toBe('2026-11-20T10:00:00.000Z');
    expect(resourceData(state)).toEqual(['杭州']);
  });

  it('五种状态互不重叠：同一份空数据不能同时被当成 empty 和 error', () => {
    const states: ResourceState<string[]>[] = [
      { status: 'idle' },
      successOrEmpty<string[]>([]),
      errorResource<string[]>('失败'),
    ];
    expect(new Set(states.map((s) => s.status))).toEqual(new Set(['idle', 'empty', 'error']));
  });
});
