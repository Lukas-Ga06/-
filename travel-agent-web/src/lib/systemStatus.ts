import type { SystemStatus } from '../api/assistant';

/**
 * 运行能力自检的展示口径（V2.2 P2/P5）。
 *
 * 后端把「配置了」和「最近一次真实调用成功」拆成两组字段：
 * - LLM：`llmConfigured` / `llmVerified`
 * - 路线：`routeConfigured`（或旧字段 `amapConfigured`）/ `amapVerified` / `routeLastError`
 *
 * 前端只做一件事：**绝不把「配置了」画成「可用」**。
 * 旧后端没有 verified 字段时，状态是「已配置但无法确认」，而不是「已验证」。
 */

export type CapabilityState =
  'unconfigured' | 'configured-unknown' | 'configured-unverified' | 'verified';

export interface CapabilityView {
  state: CapabilityState;
  /** 短标签，直接放进 <Tag> */
  label: string;
  /** antd Tag 颜色 */
  tagColor: string;
  /** 一行如实说明（含失败原因） */
  detail: string;
  /** 面向用户的「当前实际口径」，例如 真实路径 / 本地估算 */
  actual: string;
}

type LlmFields = Pick<SystemStatus, 'llmConfigured' | 'llmVerified' | 'llmModel' | 'llmHint'>;
type RouteFields = Pick<
  SystemStatus,
  | 'amapConfigured'
  | 'routeConfigured'
  | 'amapVerified'
  | 'routeLastError'
  | 'routeProvider'
  | 'routeHint'
>;

const NOT_VERIFIED_LABEL = '已配置未验证';

/** 大模型：未配置 / 已配置未验证 / 已验证 */
export function formatLlmCapability(status: LlmFields | null | undefined): CapabilityView {
  if (!status || status.llmConfigured !== true) {
    return {
      state: 'unconfigured',
      label: '未配置',
      tagColor: 'default',
      detail: status?.llmHint ?? '未配置 LLM_API_KEY：当前按规则引擎运行',
      actual: '规则引擎',
    };
  }
  const model = status.llmModel ? `（${status.llmModel}）` : '';
  if (status.llmVerified === true) {
    return {
      state: 'verified',
      label: '已验证',
      tagColor: 'green',
      detail: `最近一次真实模型调用成功${model}`,
      actual: '大模型在线',
    };
  }
  if (status.llmVerified === false) {
    return {
      state: 'configured-unverified',
      label: NOT_VERIFIED_LABEL,
      tagColor: 'orange',
      detail: `已配置 LLM_API_KEY，但最近一次真实调用失败：当前按规则引擎降级${model}`,
      actual: '规则引擎（已降级）',
    };
  }
  return {
    state: 'configured-unknown',
    label: '已配置（未上报验证状态）',
    tagColor: 'blue',
    detail: `已配置 LLM_API_KEY，但后端未上报 llmVerified（旧版本），无法确认最近调用是否成功${model}`,
    actual: '无法确认（按配置展示）',
  };
}

/** 路线规划：未配置 / 已配置未验证 / 已验证；只有 verified 才允许说「真实路径」 */
export function formatRouteCapability(status: RouteFields | null | undefined): CapabilityView {
  // 向后兼容：旧后端只有 amapConfigured，没有 routeConfigured
  const configured = status?.routeConfigured ?? status?.amapConfigured === true;
  const provider = status?.routeProvider ?? (configured ? 'amap' : 'estimated');
  const verified = status?.amapVerified === true;
  const lastError = status?.routeLastError?.trim();

  if (!configured) {
    return {
      state: 'unconfigured',
      label: '未配置',
      tagColor: 'default',
      detail: status?.routeHint ?? '未配置 AMAP_KEY / 高德 MCP：路线为本地距离估算',
      actual: '本地距离估算',
    };
  }
  if (verified && provider === 'amap') {
    return {
      state: 'verified',
      label: '已验证',
      tagColor: 'green',
      detail: status?.routeHint ?? '已配置高德 Key，且最近一次真实路径调用成功',
      actual: '高德真实路径',
    };
  }
  if (status?.amapVerified === false) {
    return {
      state: 'configured-unverified',
      label: NOT_VERIFIED_LABEL,
      tagColor: 'orange',
      detail: `已配置但尚未验证/最近调用失败${lastError ? `：${lastError}` : ''}。当前路线为本地估算`,
      actual: '本地距离估算',
    };
  }
  return {
    state: 'configured-unknown',
    label: '已配置（未上报验证状态）',
    tagColor: 'blue',
    detail: `已配置高德 Key，但后端未上报 amapVerified（旧版本），无法确认真实路径是否可用；当前口径为 ${
      provider === 'amap' ? '高德真实路径' : '本地估算'
    }`,
    actual: provider === 'amap' ? '高德真实路径（未验证）' : '本地距离估算',
  };
}
