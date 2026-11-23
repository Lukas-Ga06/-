import type { PlanFormState } from '../planForm';

/**
 * 表单字段的局部更新（由页面持有：patch 同时会关掉步骤门禁提示与草稿恢复提示）。
 * 第 1、2 步表单共用这一个签名，避免两处各写一份 setState 逻辑。
 */
export type PatchPlanForm = (patch: Partial<PlanFormState>) => void;
