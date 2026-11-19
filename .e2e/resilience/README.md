# 韧性测试脚本（V2.2：并发 / 慢接口 / 第三方失败 / 长时间运行）

全部 Node 原生（`node:` 内置模块 + 原生 `fetch`），无第三方依赖；不修改产品源码。
原始结果汇总写到 `.evidence/reports/resilience.json`，报告见 `docs/review/2026-11-19-resilience.md`。

## 端口约定

| 端口 | 用途 | 归属 |
| --- | --- | --- |
| 18090 | 主实例（LLM 关闭、限流关闭、无 AMAP_KEY） | 已有实例，**只读使用，不重启** |
| 18091 | 真实 DeepSeek Key（余额 0 → 402） | 已有实例，**只读使用** |
| 18092 / 18099 | LLM 替身链路 | 已有实例，**只读使用** |
| 18100 | 本测试自建实例（独立 H2 文件库） | 本脚本启停 |
| 18110 | 本测试的 OpenAI 兼容 LLM 替身 | 本脚本启停 |

## 文件

| 文件 | 作用 |
| --- | --- |
| `lib.mjs` | 公共库：HTTP 计时、登录/注册临时账号、分位数、并发、GBK 日志解码 |
| `manage-instance.mjs` | 自建实例 :18100 的生命周期（serve / stop / status） |
| `stub-openai-llm.mjs` | OpenAI 兼容替身：可按 mode 立即应答 / 睡 N 秒 / 返回 402 / 500 |
| `run-concurrency.mjs` | 测试 1：20 并发 chat + 10 并发 runs，重复 2 轮 |
| `run-slow-llm.mjs` | 测试 2：慢 LLM（30s 读超时）下的超时与降级 |
| `run-third-party.mjs` | 测试 3：真实 402 / 受控 402、500 / 连接拒绝 / 高德 Key |
| `run-soak.mjs` | 测试 4：12 分钟长跑 + 进程采样 |
| `soak-proc.ps1` | 长跑期间每 5s 采集 RSS / 线程数 / H2 文件大小（PowerShell 执行策略受限时用等价内联命令） |

## 运行顺序

```bash
# 0) 起替身 + 自建实例（都要挂在后台作业里，沙箱会在前台命令结束时清理子进程）
node .e2e/resilience/stub-openai-llm.mjs                       # 后台作业
node .e2e/resilience/manage-instance.mjs serve                 # 后台作业，:18100

# 1) 并发（打 18090，独立临时账号）
node .e2e/resilience/run-concurrency.mjs

# 2) 慢接口
node .e2e/resilience/run-slow-llm.mjs

# 3) 第三方失败（分部分，依赖状态不同）
node .e2e/resilience/run-third-party.mjs --part=llm402-real        # 18091 真实 402
node .e2e/resilience/run-third-party.mjs --part=stub-errors        # 替身 402 / 500
#    停掉替身（端口不再监听）后：
node .e2e/resilience/run-third-party.mjs --part=refused
#    高德：无 Key 用 18090；无效 Key 需用 --amap-key=INVALID... 重启自建实例
AMAP_PORT=18090 AMAP_VARIANT=18090_no_key  node .e2e/resilience/run-third-party.mjs --part=amap
AMAP_PORT=18100 AMAP_VARIANT=18100_invalid_key AMAP_LABEL=无效Key node .e2e/resilience/run-third-party.mjs --part=amap

# 4) 长时间运行（12 分钟；先起采样器再起请求循环）
node .e2e/resilience/run-soak.mjs --minutes=12 --interval=5000

# 收尾：停自建实例、停替身、停采样器
node .e2e/resilience/manage-instance.mjs stop
```

## 注意

- 沙箱里前台命令结束时其子进程会被清理，**java / stub 必须挂在后台作业**里常驻。
- 自建实例的 H2 是 scratch 库：每次 `serve` 会清空 `.evidence/resilience-run/data`，
  避免上一次被强杀留下的 `.lock.db` 让 H2 报 `50100`。
- 后端日志文件由 Boot 以**平台字符集（中文 Windows 上为 GBK）**写出，
  `lib.mjs` 的 `decodeLog()` 会先按 UTF-8 解、失败再按 GBK 解，否则「降级」类关键字匹配不到。