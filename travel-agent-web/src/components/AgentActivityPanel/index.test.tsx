// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import AgentActivityPanel from './index';
import type { ActivityEvent } from '../../store/stageText';

afterEach(cleanup);

function ev(event: string, data: any, time = Date.now() - 1000): ActivityEvent {
  return { event, data, time };
}

describe('AgentActivityPanel', () => {
  it('activity 为空时整块不渲染（退化为单行 stage）', () => {
    const { container } = render(<AgentActivityPanel activity={[]} />);
    expect(container.querySelector('.agent-activity')).toBeNull();
    expect(container.textContent).toBe('');
  });

  it('事件按顺序渲染成时间线行', () => {
    render(
      <AgentActivityPanel
        activity={[
          ev('start', {}),
          ev('run', { runId: '01234567-abcd-4ef0-9abc-def012345678' }),
          ev('intent', { intent: 'PLAN_TRIP' }),
          ev('planning', {}),
          ev('plan_update', {}),
          ev('confirmation', {}),
        ]}
      />,
    );

    expect(screen.getByText('已连接 Agent…')).toBeTruthy();
    expect(screen.getByText('本轮运行 01234567…')).toBeTruthy();
    expect(screen.getByText('意图识别：PLAN_TRIP')).toBeTruthy();
    expect(screen.getByText('正在规划行程…')).toBeTruthy();
    expect(screen.getByText('生成方案对比…')).toBeTruthy();
    expect(screen.getByText('等待你的确认…')).toBeTruthy();
  });

  it('tool_call 只显示工具名与参数字段名，绝不渲染参数值原文（敏感参数红线）', () => {
    const { container } = render(
      <AgentActivityPanel
        activity={[
          ev('tool_call', {
            tool: 'search_city_online',
            args: { city: 'SECRET_CITY_VALUE', days: 3, apiKey: 'SECRET_KEY_VALUE' },
          }),
        ]}
      />,
    );

    expect(container.textContent).toContain('search_city_online');
    expect(container.textContent).toContain('city、days、apiKey');
    expect(container.textContent).not.toContain('SECRET_CITY_VALUE');
    expect(container.textContent).not.toContain('SECRET_KEY_VALUE');
  });

  it('tool_result 按成功/失败渲染不同文案，error 事件渲染错误信息', () => {
    render(
      <AgentActivityPanel
        activity={[
          ev('tool_result', { tool: 'search_city_online', success: true }),
          ev('tool_result', { tool: 'geocode', success: false }),
          ev('error', { message: '规划失败：天气服务不可用' }),
        ]}
      />,
    );

    expect(screen.getByText('工具 search_city_online 执行完成')).toBeTruthy();
    expect(screen.getByText('工具 geocode 执行失败')).toBeTruthy();
    expect(screen.getByText('规划失败：天气服务不可用')).toBeTruthy();
  });

  it('未映射的事件（focus）不单独渲染成行', () => {
    const { container } = render(
      <AgentActivityPanel activity={[ev('focus', { type: 'itinerary', day: 2 })]} />,
    );
    expect(container.querySelector('.aa-row')).toBeNull();
  });
});
