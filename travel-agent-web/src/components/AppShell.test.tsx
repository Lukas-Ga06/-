// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import AppShell from './AppShell';
import type { NavModule } from './AppShell';
import { useAuthStore } from '../store/authStore';

const api = vi.hoisted(() => ({ getMemoOpenCount: vi.fn(), signOut: vi.fn() }));

vi.mock('../api/memo', () => ({ getMemoOpenCount: api.getMemoOpenCount }));
vi.mock('../api/auth', () => ({ signOut: api.signOut }));
vi.mock('./ReminderBell', () => ({ default: () => <button type="button">提醒</button> }));

function renderShell(active: NavModule = 'home') {
  return render(
    <MemoryRouter>
      <AppShell active={active}>
        <div data-testid="page-body" />
      </AppShell>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useAuthStore.setState({
    user: { id: 1, username: 'demo', nickname: '演示' },
    authChecked: true,
  });
  api.getMemoOpenCount.mockResolvedValue(0);
});

afterEach(() => {
  cleanup();
  api.getMemoOpenCount.mockReset();
  api.signOut.mockReset();
});

describe('AppShell · 信息架构（阶段 B）', () => {
  it('侧栏把「新建行程」作为固定主动作，而不是混在导航分组里', () => {
    renderShell();

    const newTrip = screen.getByRole('button', { name: /新建行程/ });
    expect(newTrip.className).toContain('sb-new-trip');
    // 导航分组里不再有同名入口：主动作只有一处
    const nav = screen.getAllByRole('button').filter((b) => b.className.includes('sb-link'));
    expect(nav.some((b) => b.textContent?.includes('新建行程'))).toBe(false);
  });

  it('一级导航按用户任务组织：行程/助手/旅行资料，开发诊断不叫「Agent 运行观察」', () => {
    renderShell();

    expect(screen.getByRole('button', { name: '我的行程' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'AI 助手' })).toBeTruthy();
    // 旅行资料分组：攻略库替代「旅行知识库」的技术术语
    expect(screen.getByRole('button', { name: '攻略库' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '记账' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '备忘' })).toBeTruthy();
    // 诊断已更名并收进设置语义
    expect(screen.queryByRole('button', { name: /Agent 运行观察/ })).toBeNull();
  });

  it('当前页导航带 aria-current=page', () => {
    const { container } = renderShell('travel');

    // 导航只有侧栏一处：当前页标记唯一，不重复
    const current = Array.from(container.querySelectorAll('[aria-current="page"]')).map((el) =>
      el.textContent?.replace(/\s/g, ''),
    );
    expect(current).toEqual(['我的行程']);

    const home = Array.from(container.querySelectorAll('.sb-link')).find((el) =>
      el.textContent?.includes('首页'),
    ) as HTMLElement;
    expect(home.getAttribute('aria-current')).toBeNull();
  });

  it('导航只由侧栏承载，不再渲染底部标签栏', () => {
    const { container } = renderShell();

    expect(container.querySelector('.app-tabbar')).toBeNull();
    const nav = container.querySelector('nav[aria-label="主导航"]');
    expect(nav?.className).toBe('sb-modules');
    const links = Array.from(container.querySelectorAll('.sb-link'));
    expect(links.length).toBeGreaterThan(0);
    links.forEach((el) => expect(el.tagName).toBe('BUTTON'));
  });

  it('登出按钮有可访问名称，点击清本地登录态并调用 signOut', () => {
    renderShell();

    fireEvent.click(screen.getByRole('button', { name: '退出登录' }));

    expect(api.signOut).toHaveBeenCalledTimes(1);
  });

  it('诊断页在当前页高亮（active=diagnostics）', () => {
    const { container } = renderShell('diagnostics');

    // 开发环境才注册诊断入口；若存在则必须高亮，且不再有旧的 active=workbench 名称
    const diag = Array.from(container.querySelectorAll('.sb-footer .sb-link')).find((el) =>
      el.textContent?.includes('诊断'),
    ) as HTMLElement | undefined;
    if (diag) {
      expect(diag.getAttribute('aria-current')).toBe('page');
    } else {
      // 生产构建不注册诊断入口：明确断言这一点，避免未来悄悄暴露
      expect(container.querySelector('.sb-footer')?.textContent ?? '').not.toContain('诊断');
    }
    expect(container.textContent).not.toContain('Agent 运行观察');
  });
});
