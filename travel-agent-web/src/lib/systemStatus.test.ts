import { describe, expect, it } from 'vitest';
import { formatLlmCapability, formatRouteCapability } from './systemStatus';

/**
 * P2/P5 的用户可见面：配置态 ≠ 可用态。
 * 这些用例锁住口径——「已配置但没验证」绝不能显示成「已验证/真实路径」。
 */
describe('运行能力自检展示口径', () => {
  describe('路线规划', () => {
    it('未配置：如实说明本地估算，不说真实路径', () => {
      const view = formatRouteCapability({
        amapConfigured: false,
        routeConfigured: false,
        amapVerified: false,
        routeProvider: 'estimated',
        routeLastError: null,
        routeHint: '路线为本地距离估算（未配置 AMAP_KEY / 高德 MCP）',
      });
      expect(view.state).toBe('unconfigured');
      expect(view.label).toBe('未配置');
      expect(view.actual).toBe('本地距离估算');
      expect(view.detail).toContain('本地距离估算');
      expect(view.detail).not.toContain('使用高德真实路径');
    });

    it('已配置但最近调用失败：显示「已配置但尚未验证/最近调用失败」+ 失败原因，不宣称真实路径', () => {
      const view = formatRouteCapability({
        amapConfigured: true,
        routeConfigured: true,
        amapVerified: false,
        routeProvider: 'estimated',
        routeLastError: 'status=0 infocode=10001 INVALID_USER_KEY',
        routeHint: '高德已配置但最近一次真实调用失败：当前路线为本地估算',
      });
      expect(view.state).toBe('configured-unverified');
      expect(view.label).toBe('已配置未验证');
      expect(view.detail).toContain('已配置但尚未验证/最近调用失败');
      expect(view.detail).toContain('10001');
      expect(view.actual).toBe('本地距离估算');
      expect(view.actual).not.toContain('真实路径');
      expect(view.detail).not.toContain('使用高德真实路径');
    });

    it('已验证：只有最近一次真实调用成功才显示真实路径', () => {
      const view = formatRouteCapability({
        amapConfigured: true,
        routeConfigured: true,
        amapVerified: true,
        routeProvider: 'amap',
        routeLastError: null,
        routeHint: '路线使用高德真实路径规划（打车费用仍为本地估算）',
      });
      expect(view.state).toBe('verified');
      expect(view.label).toBe('已验证');
      expect(view.actual).toBe('高德真实路径');
    });

    it('旧后端没有 routeConfigured/amapVerified：按「配置了但无法确认」展示，不冒充已验证', () => {
      const view = formatRouteCapability({
        amapConfigured: true,
        geoProvider: 'amap',
        geoHint: '高德 Web 服务已配置',
        timezone: 'Asia/Shanghai',
      } as any);
      expect(view.state).toBe('configured-unknown');
      expect(view.label).toContain('未上报验证状态');
      expect(view.label).not.toBe('已验证');
      expect(view.detail).toContain('无法确认真实路径是否可用');
    });
  });

  describe('大模型', () => {
    it('未配置：规则引擎', () => {
      const view = formatLlmCapability({
        llmConfigured: false,
        llmHint: '未配置 LLM_API_KEY：当前为规则引擎模式',
        llmModel: null,
      });
      expect(view.state).toBe('unconfigured');
      expect(view.actual).toBe('规则引擎');
      expect(view.detail).toContain('规则引擎');
    });

    it('已配置但最近调用失败：不显示「已接入/在线」', () => {
      const view = formatLlmCapability({
        llmConfigured: true,
        llmVerified: false,
        llmModel: 'deepseek-chat',
        llmHint: '已接入大模型',
      });
      expect(view.state).toBe('configured-unverified');
      expect(view.label).toBe('已配置未验证');
      expect(view.actual).toContain('降级');
      expect(view.detail).toContain('最近一次真实调用失败');
      expect(view.detail).not.toContain('在线');
    });

    it('已验证：最近调用成功才显示在线', () => {
      const view = formatLlmCapability({
        llmConfigured: true,
        llmVerified: true,
        llmModel: 'deepseek-chat',
        llmHint: '已接入大模型',
      });
      expect(view.state).toBe('verified');
      expect(view.actual).toBe('大模型在线');
    });

    it('旧后端缺 llmVerified：按「已配置但无法确认」展示', () => {
      const view = formatLlmCapability({
        llmConfigured: true,
        llmHint: '已接入大模型',
        llmModel: 'deepseek-chat',
      });
      expect(view.state).toBe('configured-unknown');
      expect(view.label).not.toBe('已验证');
      expect(view.detail).toContain('无法确认最近调用是否成功');
    });
  });
});