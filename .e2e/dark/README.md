# 暗色地图 / 暗色图表 / 自检可见性 验收（V2.2）

本目录是一次可复现的真实验收，不是单测替身：

| 文件 | 作用 |
| --- | --- |
| `start-instance.mjs` | 起后端 **18096** + 独立 H2（`.evidence/dark/run/data`）；LLM 关闭、demo 账号开启 |
| `verify-dark.mjs` | Playwright Chromium 播种行程 → 浅/暗地图截图 → 预算/记账抽屉截图 → 健康页自检文案；产出 `.evidence/dark/dark-evidence.json` |
| `analyze-shots.py` | 对截图做像素亮度/对比度量化（Pillow + numpy），产出 `.evidence/dark/luminance-analysis.json` |

## 复现步骤

```powershell
# 1) 构建前端（dist 里内嵌 .env.local 的 JS API Key）
cd travel-agent-web; npm run build; cd ..

# 2) 后端：18096 + 独立 H2；无效 AMAP Key 用于拿到真实的 routeLastError
node .e2e/dark/start-instance.mjs serve --amap-key=INVALID_DARK_VERIFY_KEY

# 3) 静态站：4173 反代到 18096
node .e2e/mobile/serve-dist.mjs --port 4173 --api http://127.0.0.1:18096

# 4) 浏览器验收 + 量化
node .e2e/dark/verify-dark.mjs
D:\dsh-home\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe .e2e/dark/analyze-shots.py

# 5) 移动端 PWA 回归（iPhone 13 WebKit + Pixel 7 Chromium）
node .e2e/mobile/mobile-pwa-check.mjs --base http://127.0.0.1:4173 --api http://127.0.0.1:18096 `
  --out .evidence/dark/mobile-pwa.json --screenshots .evidence/dark/pwa-screens

# 6) 清理
node .e2e/dark/start-instance.mjs stop
```

## 口径（为什么用无效 Key）

- 地图底图样式由前端 JS API Key（`.env.local`）决定，与后端 Web 服务 Key 无关；
  后端配无效 Key 不影响暗色底图验证。
- 后端配无效 Key 后 `/api/system/status` 会如实给出 `routeConfigured=true`、`amapVerified=false`、
  `routeLastError=infocode=10001, info=INVALID_USER_KEY`，正好用来证明健康页
  「已配置未验证」这条关键文案，而不是把「配置了」画成「可用」。
- `/route-legs` 因此全是 `estimated`（预期）：不伪造真实几何，地图只画估算走向。