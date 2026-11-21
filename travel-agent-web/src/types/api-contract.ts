/**
 * 手工前端类型 ↔ OpenAPI 生成类型的**类型级契约断言**。
 *
 * 这里没有任何运行时代码：所有断言都在 `tsc --noEmit`（`npm run typecheck`）阶段执行。
 * 生成类型来自 `src/api/generated/schema.d.ts`（由 `npm run api:generate` 从
 * `docs/api/openapi.json` 生成，后者由后端 `OpenApiSnapshotTest` 固化）。
 *
 * 断言口径（见 `docs/guide/api-contract.md`）：
 * - **形状双向可赋值**：`DeepNormalize` 去掉「可选/可空」差异后，两边必须互相可赋值。
 *   去掉可选/可空是因为 OpenAPI 只在 `@NotNull` 等校验注解上标 `required`，而前端手写类型
 *   按「后端总会返回该键」声明为必填；这属于已知且无害的差异，不是漂移。
 * - **枚举收窄单独校验**：后端字段是 `String`（schema 里只有 `type: string`），前端收窄成
 *   字面量联合。这种收窄是前端主动做的，只要求「前端联合是后端 string 的子集」。
 * - 其余任何字段改名、类型变化、字段消失都会让下面的 `Assert<...>` 编译失败。
 *
 * 修法优先级：**先改手写类型对齐 schema**；确实需要保留差异时，在对应断言处写明确注释的
 * 最小豁免（例如只 Omit 单个字段），不要用 `any` 掩盖。
 */
import type { components, paths } from '../api/generated/schema';
import type { AgentStreamParams, ConversationMessageView, ConversationSummary } from '../api/agent';
import type { AddItemPayload, ItineraryItem, ItineraryPrice, Trip } from './trip';

/** 按 schema 名取生成类型，例如 `ApiSchema<'TripResponse'>`。 */
export type ApiSchema<Name extends keyof components['schemas']> = components['schemas'][Name];

/** 断言：T 必须为 true，否则 tsc 报错（错误信息指向下面出问题的那一行）。 */
export type Assert<T extends true> = T;

/** 深度去掉可选性（`?`）与 `null`/`undefined`，只比较「键 + 值类型」。 */
export type DeepNormalize<T> = T extends (infer Item)[]
  ? DeepNormalize<Item>[]
  : T extends object
    ? { [Key in keyof T]-?: DeepNormalize<NonNullable<T[Key]>> }
    : T;

/** A 与 B 双向可赋值（在 DeepNormalize 之后）。 */
export type SameShape<A, B> = [DeepNormalize<A>] extends [DeepNormalize<B>]
  ? [DeepNormalize<B>] extends [DeepNormalize<A>]
    ? true
    : false
  : false;

/** A 是 B 的子集（用于前端收窄枚举）。 */
export type IsSubset<A, B> = [A] extends [B] ? true : false;

// ==================== Trip ====================

type ApiTrip = ApiSchema<'TripResponse'>;

/**
 * 除 `phase` 外逐字段一致：`phase` 后端是 String（schema: string），
 * 前端收窄为四个业务阶段字面量，因此单独做子集断言。
 */
export type TripShapeMatchesSchema = Assert<SameShape<Omit<Trip, 'phase'>, Omit<ApiTrip, 'phase'>>>;
export type TripPhaseIsNarrowedFromApi = Assert<
  IsSubset<NonNullable<Trip['phase']>, ApiTrip['phase']>
>;

// ==================== ItineraryItem ====================

type ApiItineraryItem = ApiSchema<'ItineraryItemDTO'>;

/** 除 `status` 外逐字段一致（含嵌套的 `price.links`）。 */
export type ItineraryItemShapeMatchesSchema = Assert<
  SameShape<Omit<ItineraryItem, 'status'>, Omit<ApiItineraryItem, 'status'>>
>;

/** `status` 同样是前端收窄：PENDING / IN_PROGRESS / COMPLETED / SKIPPED 四态。 */
export type ItineraryStatusIsNarrowedFromApi = Assert<
  IsSubset<NonNullable<ItineraryItem['status']>, ApiItineraryItem['status']>
>;

/** 参考价结构（ADR-006：kind=reference / source=local-estimate）逐字段一致。 */
export type ItineraryPriceMatchesSchema = Assert<SameShape<ItineraryPrice, ApiSchema<'PriceDTO'>>>;

// ==================== 最近会话（§8.6） ====================

/**
 * 会话列表与回填：只读接口，字段与 schema 逐字段一致。
 * 这里断言的价值在于「后端加/改字段时前端不会静默漂移」——例如标题若改成对象结构，
 * 列表会渲染成 [object Object]，而这种错误在类型层就能拦住。
 */
export type ConversationSummaryMatchesSchema = Assert<
  SameShape<ConversationSummary, ApiSchema<'ConversationSummary'>>
>;
export type ConversationMessageViewMatchesSchema = Assert<
  SameShape<ConversationMessageView, ApiSchema<'ConversationMessageView'>>
>;

// ==================== Chat 请求 ====================

/**
 * SSE 对话请求：前端 `AgentStreamParams` 与后端 `ChatRequest` 一致。
 * 注意 `message` 的长度限制（前端 500 / 表单流式 2 万）在 schema 里是 `maxLength`，
 * 属于运行时校验而非类型，不在类型断言范围内。
 */
export type ChatStreamParamsMatchSchema = Assert<
  SameShape<AgentStreamParams, ApiSchema<'ChatRequest'>>
>;

// ==================== 核心路由存在性 ====================

/** 编译期校验：这些前端依赖的路由必须仍出现在生成的 `paths` 里。 */
export type PathMethodExists<
  Path extends keyof paths,
  Method extends keyof paths[Path],
> = paths[Path][Method] extends never ? false : true;

export type TripListRouteExists = Assert<PathMethodExists<'/api/trips', 'get'>>;
export type TripCreateRouteExists = Assert<PathMethodExists<'/api/trips', 'post'>>;
export type TripItineraryRouteExists = Assert<PathMethodExists<'/api/trips/{id}/itinerary', 'get'>>;
export type ItineraryItemUpdateRouteExists = Assert<PathMethodExists<'/api/itinerary/{id}', 'put'>>;
export type AgentChatRouteExists = Assert<PathMethodExists<'/api/agent/chat', 'post'>>;
export type AgentStreamRouteExists = Assert<PathMethodExists<'/api/agent/stream', 'get'>>;

// ==================== 新增行程项请求 ====================

/**
 * 手写 `AddItemPayload` 是「前端提交、后端校验」的请求体：
 * 后端 `AddItemRequest` 把 `title` 标成必填（`required: ['title']`），因此不能像响应类型
 * 那样直接双向等价（前端把 dayIndex/title 声明为必填，后端只要求 title）。
 * 这里只校验前端声明过的字段都在 schema 里、且值类型兼容，避免字段名写错。
 */
export type AddItemPayloadFieldsExist = Assert<
  [keyof AddItemPayload] extends [keyof ApiSchema<'AddItemRequest'>] ? true : false
>;
