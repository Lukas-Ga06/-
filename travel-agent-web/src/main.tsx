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
          colorPrimary: '#007aff',
          colorInfo: '#007aff',
          colorBgLayout: 'transparent',
          colorBgContainer: '#ffffff',
          colorText: '#1c1c1e',
          colorTextSecondary: 'rgba(60, 60, 67, 0.62)',
          colorBorder: 'rgba(60, 60, 67, 0.16)',
          colorBorderSecondary: 'rgba(60, 60, 67, 0.12)',
          borderRadius: 14,
          fontSize: 14,
          boxShadowSecondary: '0 12px 40px rgba(31, 41, 92, 0.18)',
        },
        components: {
          Button: { fontWeight: 600, primaryShadow: 'none' },
          Card: { borderRadiusLG: 18 },
          Modal: { borderRadiusLG: 18 },
          Segmented: { itemSelectedBg: '#ffffff' },
          Progress: { defaultColor: '#007aff' },
          Tooltip: { colorBgSpotlight: 'rgba(28, 28, 30, 0.92)' },
        },
      }}
    >
      <AntApp>
        {/* 液态玻璃画布：浅色极光，供玻璃面板折射 */}
        <div className="aurora" aria-hidden>
          <span className="a1" />
          <span className="a2" />
          <span className="a3" />
          <span className="a4" />
        </div>
        <App />
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
