# Android 真机验收模板（§5.1 矩阵）

> 用途：两台真机到位后，按本模板逐项打勾并留证，产出 `docs/review/<日期>-android-device.md`。
> 状态：**模板，未执行** ｜ 生成日期：2026-11-25 ｜ 矩阵来源：[上线执行清单 §5.1](../operations/launch-checklist.md)
> 口径：本模板只收**真机**结果。Playwright WebKit / Chromium 预设（21/21 通过）与 Chromium Android 仿真
> 都不算，见 [设备与弱网验收](../review/2026-11-21-device-network-acceptance.md) §1。

## 1. 设备与构建信息（每台一份）

| 字段 | 设备 A | 设备 B |
| --- | --- | --- |
| 型号 / 厂商 | | |
| Android 版本 / 安全补丁 | | |
| ROM 与版本（原生 / MIUI / ColorOS / 鸿蒙） | | |
| Chrome 版本（或微信 WebView 内核版本） | | |
| 屏幕物理分辨率 / DPR | | |
| 导航方式（手势 / 三键） | | |
| 网络条件（Wi-Fi / 5G / 弱网模拟方式） | | |
| 被测构建（commit / 镜像 tag / URL） | | |
| 测试时间与执行人 | | |

取设备信息（USB 调试打开后）：

```bash
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.security_patch
adb shell dumpsys package com.android.chrome | grep versionName
adb shell wm size; adb shell wm density
```

> Windows 上若无 `adb`，装 platform-tools 后把目录加入 PATH；没有设备时 `adb devices` 只显示
> `List of devices attached`（11-21 快照记录的就是这个状态）。

## 2. 十维矩阵（每维：截图 + 结论）

判定列只能填 `通过` / `不通过` / `不适用（写原因）`；"不通过"必须附复现步骤。

| # | 维度 | 验收点 | 通过标准 | 设备 A | 设备 B | 截图编号 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 视口 | 360×800、393×873、412×915、768×1024；横竖屏 | 无横向滚动；长标题 ≤2 行 | | | |
| 2 | 安全区 | 手势导航 + 三键导航 | 底部 Tab、sticky 输入框不被系统条遮挡 | | | |
| 3 | 软键盘 | 聊天输入、记账金额、日期选择 | 输入框不被键盘盖住，页面不跳动 | | | |
| 4 | 返回 | 系统返回键 / 返回手势 | 不误退出 PWA，不丢未确认的 Diff | | | |
| 5 | PWA | Chrome 菜单「安装应用」→ 桌面图标 → standalone 启动 | 正常启动；离线只读行程；恢复后同步 | | | |
| 6 | 地图 | 高德 JS API 手势缩放、点选 POI | 可交互；失败显示降级文案与重试 | | | |
| 7 | 导出 | 路书 PDF 下载、打开、分享 | 下载管理器能打开，文件名正常 | | | |
| 8 | 弱网 | 飞行模式 / 弱信号 | 明确的 loading/降级状态，不白屏 | | | |
| 9 | 微信内打开 | 公众号链接 → 登录 → 规划 | 可在 WebView 内完成，不被拦截 | | | |
| 10 | 登录态 | 关闭浏览器再打开 | 保持登录 | | | |

## 3. 证据要求

- 每条维度至少 1 张截图，命名 `dev{A|B}-{序号}-{维度}.png`（例：`devA-03-keyboard.png`）；
- 截图放 `docs/review/assets/<日期>-android/`（或 `.evidence/android-<日期>/`，二选一并在报告里写明）；
- 截图必须含系统状态栏（能看出时间、信号、电量），否则无法证明是同一台真机同一时段；
- 不通过项附 `adb logcat -d -t 200` 片段或录屏。

## 4. 报告骨架

```markdown
# Android 真机验收（YYYY-MM-DD）

> 快照日期：YYYY-MM-DD ｜ 验证范围：§5.1 十维矩阵 ×2 台 ｜ 环境：<型号/版本/ROM/Chrome> ｜ 结论：<通过项>/10 ×2

## 1. 设备信息
（贴第 1 节两张表）

## 2. 逐维结果
（贴第 2 节矩阵，填满）

## 3. 缺陷清单
| # | 维度 | 现象 | 复现步骤 | 严重级 | 处置 |
## 4. 证据索引
## 5. 结论
通过 / 条件通过（列出必须在灰度前修的项）/ 不通过
```

## 5. 已知会在真机上才暴露的三类问题（重点看）

1. **软键盘 + sticky 输入框**：桌面仿真不会真的弹键盘，`visualViewport` 行为只有真机可靠；
2. **微信 WebView**：X5 内核与 Chrome 的 CSS 支持差异、下载被拦、`window.open` 被改写；
3. **PWA 安装与离线**：Chrome 菜单路径、standalone 启动后的安全区、Service Worker 更新时机。

相关文档：[上线执行清单 §5.1](../operations/launch-checklist.md) · [设备与弱网验收](../review/2026-11-21-device-network-acceptance.md) · [移动端 PWA 验收](../review/2026-11-19-mobile-pwa.md) · [外部阻断项复核](../review/2026-11-25-external-blockers-closeout.md) §2