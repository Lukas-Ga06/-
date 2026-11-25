# 安全政策

> 适用范围：`main` 与最新 release tag ｜ 报告渠道：GitHub Private vulnerability reporting ｜ 最后更新：2026-11-24

## 1. 支持的版本

| 版本 | 安全修复 |
| --- | --- |
| `main`（最新提交） | ✅ 修复直接落入 |
| 最新 release tag（当前 `v0.1.1`） | ✅ 按影响面回补 |
| 更早的 tag / 分支 | ❌ 不单独维护 |

v1 之前 API 与数据模型仍可能变动，安全修复只保证落在 `main`；需要长期维护的版本请先开 issue 讨论。

## 2. 报告漏洞

**请不要开公开 issue，也不要在 PR / 讨论区贴利用细节。**

首选 GitHub 的私密渠道：[提交安全公告](https://github.com/Lukas-Ga06/-/security/advisories/new)（仓库已开启 Private vulnerability reporting，仅维护者可见）。

一份有用的报告通常包含：

- 受影响的版本 / 提交，以及接口、文件或配置项；
- 复现步骤或最小 PoC；
- 影响判断（越权读取他人数据、身份绕过、密钥泄露、SSRF、拒绝服务等）；
- 是否已在公开渠道出现。

如果暂时无法使用 GitHub 的私密渠道，可以在普通 issue 里**只写**「我有一个安全问题，请提供其他私密联系方式」，不要附细节，我们会跟进。

## 3. 响应预期

这是个人维护的项目，时限为尽力而为，不构成商业 SLA：

- 3 个工作日内确认收到；
- 10 个工作日内给出初步定性（接受 / 需要更多信息 / 不适用）；
- 修复与公开披露的时间按影响面协商；修复后会在 [CHANGELOG](docs/CHANGELOG.md) 记录，致谢方式可匿名。

## 4. 不在范围内

- 需要物理接触设备，或依赖已失陷宿主环境的问题；
- 上游依赖自身的 0-day（请同时报给上游；本项目用 Dependabot 与 Trivy 跟踪）；
- 社会工程、钓鱼；
- 演示账号 `demo/demo123` 在显式开启 `DEMO_ACCOUNT=true` 时的预期行为（生产应设为 `false`）；
- 未配置外部 Key 时的降级 / 规则规划行为（见[能力边界](docs/guide/capability-boundaries.md)）；
- 仅来自自动化扫描器的低信号输出（缺少 CSP、HSTS 等加固建议）——欢迎开普通 issue 讨论。

## 5. 凭据处理

- 生产密钥只通过环境变量注入，不入库；`.env.local`、`data/`、`.evidence/` 已在 `.gitignore` 中。
- 仓库转为公开前，已用 gitleaks 8.30.1 扫过全部分支与历史：7 条命中均为测试值 / 占位符，无真实凭据。
- CI 对每个 PR 跑 `gitleaks` 全历史扫描（`.github/workflows/ci.yml` 的 `secret-scan` job，配置见 `.gitleaks.toml`）。
- 如果你在历史提交里发现了真实凭据，请走上面的私密渠道；我们会立即轮换，并评估是否需要改写历史。

## 6. 许可与法律

本文件是工程侧的报告指引，不构成法律承诺；使用许可与责任边界以 [LICENSE](LICENSE) 为准。