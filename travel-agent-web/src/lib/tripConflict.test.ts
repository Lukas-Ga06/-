import { describe, expect, it } from 'vitest';
import {
  conflictNotice,
  hasRemoteUpdate,
  isTripConflict,
  TRIP_CONFLICT_MESSAGE,
} from './tripConflict';

/**
 * 阶段 D 冲突提示的纯逻辑：文案、409 判定、版本比较。
 * 页面/队列/助手对这三件事的反应各有独立用例，这里只锁语义边界。
 */
describe('tripConflict · 409 判定', () => {
  it('ApiError 形态（status=409）算冲突', () => {
    expect(isTripConflict(Object.assign(new Error('冲突'), { status: 409 }))).toBe(true);
  });

  it('axios 原始错误形态（response.status=409）也算冲突', () => {
    expect(isTripConflict({ response: { status: 409 } })).toBe(true);
  });

  it('400 / 404 / 500 与网络错误都不算冲突', () => {
    expect(isTripConflict(Object.assign(new Error('参数错误'), { status: 400 }))).toBe(false);
    expect(isTripConflict(Object.assign(new Error('不存在'), { status: 404 }))).toBe(false);
    expect(isTripConflict(Object.assign(new Error('服务端错误'), { status: 500 }))).toBe(false);
    expect(isTripConflict(Object.assign(new Error('断网'), { code: 'ERR_NETWORK' }))).toBe(false);
    expect(isTripConflict(null)).toBe(false);
  });
});

describe('tripConflict · 版本比较', () => {
  it('版本号变了说明别处更新过', () => {
    expect(hasRemoteUpdate(3, 4)).toBe(true);
  });

  it('版本号相同 = 没有远程更新', () => {
    expect(hasRemoteUpdate(4, 4)).toBe(false);
  });

  it('旧后端不返回 version（undefined/null）时一律不提示', () => {
    expect(hasRemoteUpdate(undefined, 4)).toBe(false);
    expect(hasRemoteUpdate(4, undefined)).toBe(false);
    expect(hasRemoteUpdate(null, null)).toBe(false);
  });
});

describe('tripConflict · 文案', () => {
  it('统一说「此行程已在其他位置更新」，并说明已刷新', () => {
    expect(TRIP_CONFLICT_MESSAGE).toBe('此行程已在其他位置更新');
    expect(conflictNotice()).toBe('此行程已在其他位置更新，已为你刷新到最新');
  });

  it('可以替换后半句（例如队列放弃重试的场景）', () => {
    expect(conflictNotice('已放弃这条待同步变更')).toBe(
      '此行程已在其他位置更新，已放弃这条待同步变更',
    );
  });
});
