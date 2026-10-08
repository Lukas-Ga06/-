import React, { useEffect } from 'react';
import ReactDOM from 'react-dom/client';
import { App as AntApp, ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import 'antd/dist/reset.css';
import './styles/global.css';
import App from './App';
import { applyTheme, useThemeStore } from './store/themeStore';

/** antd 与自定义样式共用同一套色值：改色板时两处一起改 */
const PALETTE = {
  light: {
    colorBgLayout: '#f6f7f9',
    colorBgContainer: '#ffffff',
    colorText: '#111827',
    colorTextSecondary: '#5b6472',
    colorTextTertiary: '#8b93a1',
    colorBorder: '#e4e6eb',
    colorBorderSecondary: '#eef0f4',
    colorPrimary: '#1f6feb',
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

function Root() {
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
          colorSuccess: dark ? '#4ade80' : '#16a34a',
          colorWarning: dark ? '#fbbf24' : '#d97706',
          colorError: dark ? '#f87171' : '#dc2626',
          borderRadius: 8,
          fontSize: 14,
          controlHeight: 34,
          fontFamily:
            '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
          boxShadowSecondary: dark
            ? '0 4px 12px rgba(0, 0, 0, 0.45)'
            : '0 4px 12px rgba(16, 24, 40, 0.08)',
        },
        components: {
          Button: { fontWeight: 600, primaryShadow: 'none', defaultShadow: 'none' },
          Card: { borderRadiusLG: 12 },
          Modal: { borderRadiusLG: 12 },
          Drawer: { borderRadiusLG: 12 },
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

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Root />
  </React.StrictMode>,
);
