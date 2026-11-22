# 阶段 D 收口验收：冲突提示「此行程已在其他位置更新」（2026-11-21）

> 快照日期：2026-11-21 ｜ 验证范围：行程写冲突的前后端契约、409 处理、回到页面的版本对齐 ｜ 环境：Windows / JDK 17 / Node 20.17.0 / H2（测试） ｜ 结论：**冲突可被识别、提示并自动刷新，门禁全绿**；真实双端并发未验

## 1. 问题与口径

行程有 `@Version` 乐观锁（`Trip.java:70`）：两个标签页/设备同时改同一行程时，后提交的一方被后端拒绝并回
**409「数据已被其他操作修改，请刷新后重试」**（`GlobalExceptionHandler:75`，有 `OptimisticLockConflictTest` 覆盖）。
但前端此前把 409 当普通错误：`message.error(e.message)`，用户不清楚发生了什么，也不会刷新，
继续操作只会重复失败。阶段 D 的要求是「增加离线待同步、冲突和撤销反馈」中的**冲突**这一项。

冲突的处理口径（刻意保守）：

- **只提示 + 刷新，不自动合并**。谁后写谁赢，前端没有依据判断用户想保留哪一份；
- 提示文案统一为「**此行程已在其他位置更新**」，并说明已经做了什么（刷新 / 放弃待同步变更）；
- 没有版本信息时（旧后端）不做主动判断 —— 宁可不提示，也不误报。

## 2. 交付内容

| 层 | 改动 | 位置 |
| --- | --- | --- |
| 后端契约 | `TripResponse` 暴露只读 `version`（来自既有 `@Version`），OpenAPI 快照与前端生成类型同步 | `dto/TripDtos.java`、`docs/api/openapi.json`、`src/api/generated/schema.d.ts` |
| 冲突判定 | 409 判定、版本比较、统一文案（纯函数，无 React 依赖） | `src/lib/tripConflict.ts` |
| 写失败 | 行程项状态推进 / 撤销遇到 409：提示 + 重拉行程、时间轴、转场段 | `src/pages/Trip/index.tsx` |
| 主动发现 | 回到页面（`visibilitychange` → visible）时 `syncTrip` 对比版本；变了就提示并刷新，3s 内合并重复事件 | `src/store/tripStore.ts`、`src/pages/Trip/index.tsx` |
| 待同步队列 | 重放遇到 409：这条变更的前提已失效，**出队**（不再无限重试）并在同步结果里说明 | `src/store/statusQueueStore.ts` |
| 方案确认 | 确认方案遇到 409：聊天里明确「本次提交未生效，方案已保留」，而不是笼统的「提交失败」 | `src/store/agentStore.ts` |

## 3. 契约链路

`version` 是**只读**字段：客户端不提交、不参与写请求，只用于比较。这样不改变写接口的兼容性
（旧客户端照常工作），也让「别处改过」第一次有了可判定的依据。

链路与门禁联动：

1. 后端 `TripDtos.TripResponse.version`；
2. `mvn -Dtest=OpenApiSnapshotTest -Dopenapi.update=true test` 更新 `docs/api/openapi.json`（+4 行，无其它漂移）；
3. `npm run api:generate` 重新生成 `schema.d.ts`；
4. `src/types/api-contract.ts` 的类型级断言会在「手写 `Trip.version` 与 schema 不一致」时直接让 `tsc` 失败
   —— 本轮加字段时它先报错，补完类型才通过，说明这条门禁真的在拦。

## 4. 验证结果

- 后端：`mvn -B verify` **BUILD SUCCESS**（覆盖率检查通过、Checkstyle 0 violation、SpotBugs 0 bug）。
  新增 `TripLifecycleTest#versionIsExposedAndBumpedOnUpdate`：GET 返回 `version`，PUT 改一次后 `version + 1`。
- 前端：`npm test` **47 文件 / 482 用例全过**，本轮新增 14 条：
  - `lib/tripConflict.test.ts` 8 条（409 三种形态、400/404/500/网络不算冲突、版本比较与缺省、文案）；
  - `store/tripStore.test.ts` 3 条（版本变了才返回 true、旧后端不误报、不把非当前行程写回）；
  - `store/statusQueueStore.test.ts` 1 条（409 出队且后续条目继续同步）；
  - `store/agentStore.test.ts` 1 条（确认冲突时保留方案并给出冲突文案）；
  - `pages/Trip/index.test.tsx` 1 条（409 时提示 + 重拉行程与时间轴、不入队、不留撤销条）。
- 既有用例 `axios 原始错误的 response.status 也算服务端明确回应` 原先用 409 当「普通业务拒绝」，
  语义已变，改为 422 并注明原因（409 现在走冲突分支）。
- 视觉：`npm run test:visual` **11/11 像素一致**（本阶段只加提示与后台请求，不改布局）。
- `typecheck` / `lint`（0 error）/ `format:check` / `build:check` / `api:check` 全绿。

## 5. 边界与仍待办

- **行程项状态接口没有 `@Version`**：两个设备分别改不同行程项的先后顺序目前是「后写覆盖」，
  不会产生 409。真正的冲突来自**行程级**改动（确认方案、改日期/预算等）。要让每一项都可检测，
  需要给 `ItineraryItem` 加版本或让状态接口带上父行程版本 —— 属于契约变更，未在本轮做。
- **真实双端并发未验**：单测用 409 桩、后端用 MockMvc 验证版本递增；两台设备同时操作的真实时序
  （含弱网重放）仍需真机 + 真实后端，列入第 6 节待办。
- 冲突提示目前是 antd `message`（3 秒自动消失）；如果将来需要「保留冲突直到用户处理」，
  应换成可停留的通知/横幅，并明确「以哪一份为准」的操作。