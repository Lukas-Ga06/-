import { useEffect } from 'react';
import { App as AntApp, ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import { applyTheme, useThemeStore } from './store/themeStore';

/** antd 与自定义样式共用同一套色值：改色板时两处一起改 */
const PALETTE = {
  light: {
    colorBgLayout: '#f6f7f9',
    colorBgContainer: '#ffffff',
    colorText: '#111827',
    colorTextSecondary: '#5b6472',
    // AA：原 #8b93a1 在白底只有 3.09:1（登录条款、次要说明会不达标）
    colorTextTertiary: '#6b7280',
    colorBorder: '#e4e6eb',
    colorBorderSecondary: '#eef0f4',
    // AA：白字落在主色上 4.63:1，压暗一档后 5.85:1（选中 Tab / 主按钮同源）
    colorPrimary: '#1a5fd0',
  },
  dark: {
    colorBgLayout: '#0f1115',
    colorBgContainer: '#161920',
    colorText: '#e8eaee',
    colorTextSecondary: '#a0a7b4',
    colorTextTertiary: '#6f7885',
    colorBorder: '#262b35',
    colorBorderSecondary: '#1f242d',
    colorPrimary: '#5b9bff',
  },
};

/** 应用根：主题令牌 + antd 语言包 + 全局 App 容器 */
export default function Root() {
  const mode = useThemeStore((s) => s.mode);
  const dark = mode === 'dark';

  // 与 index.html 的首帧预设保持一致：切换时同步到 <html data-theme>
  useEffect(() => {
    applyTheme(mode);
  }, [mode]);

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
        token: {
          ...PALETTE[mode],
          colorInfo: PALETTE[mode].colorPrimary,
          // AA：Tag/成功文本用 colorSuccess，原 #16a34a 在 #f6ffed 上只有 3.3:1
          colorSuccess: dark ? '#4ade80' : '#16803c',
          colorWarning: dark ? '#fbbf24' : '#b45309',
          colorError: dark ? '#f87171' : '#c62828',
          // 控件圆角 6px（P1-G 令牌）；卡片 8、弹窗/抽屉 10 在 components 里单独给
          borderRadius: 6,
          fontSize: 14,
          controlHeight: 36,
          controlHeightSM: 32,
          controlHeightLG: 40,
          fontFamily:
            '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
          boxShadowSecondary: dark
            ? '0 4px 12px rgba(0, 0, 0, 0.45)'
            : '0 4px 12px rgba(16, 24, 40, 0.08)',
        },
        components: {
          Button: { fontWeight: 600, primaryShadow: 'none', defaultShadow: 'none' },
          Card: { borderRadiusLG: 8 },
          Modal: { borderRadiusLG: 10 },
          Drawer: { borderRadiusLG: 10 },
          Segmented: { itemSelectedBg: PALETTE[mode].colorBgContainer },
          Progress: { defaultColor: PALETTE[mode].colorPrimary },
          Tooltip: {
            colorBgSpotlight: dark ? 'rgba(232, 234, 238, 0.92)' : 'rgba(17, 24, 39, 0.92)',
          },
          Table: { headerBg: PALETTE[mode].colorBgLayout },
        },
      }}
    >
      <AntApp>
        <App />
      </AntApp>
    </ConfigProvider>
  );
}
