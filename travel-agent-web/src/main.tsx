import React from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import 'antd/dist/reset.css';
import './styles/global.css';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#1f6feb',
          colorInfo: '#1f6feb',
          colorSuccess: '#16a34a',
          colorWarning: '#d97706',
          colorError: '#dc2626',
          colorBgLayout: '#f6f7f9',
          colorBgContainer: '#ffffff',
          colorText: '#111827',
          colorTextSecondary: '#5b6472',
          colorTextTertiary: '#8b93a1',
          colorBorder: '#e4e6eb',
          colorBorderSecondary: '#eef0f4',
          borderRadius: 8,
          fontSize: 14,
          controlHeight: 34,
          fontFamily:
            '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
          boxShadowSecondary: '0 4px 12px rgba(16, 24, 40, 0.08)',
        },
        components: {
          Button: { fontWeight: 600, primaryShadow: 'none', defaultShadow: 'none' },
          Card: { borderRadiusLG: 12 },
          Modal: { borderRadiusLG: 12 },
          Drawer: { borderRadiusLG: 12 },
          Segmented: { itemSelectedBg: '#ffffff' },
          Progress: { defaultColor: '#1f6feb' },
          Tooltip: { colorBgSpotlight: 'rgba(17, 24, 39, 0.92)' },
          Table: { headerBg: '#f9fafb' },
        },
      }}
    >
      <AntApp>
        <App />
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
