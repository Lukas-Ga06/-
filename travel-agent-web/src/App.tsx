import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import type { ReactNode } from 'react';
import { Spin } from 'antd';
import { useAuthStore } from './store/authStore';

// 路由级懒加载：echarts / 地图库等仅在对应页面打开时才下载，
// 避免所有页面 + 图表库 + 两套地图引擎都塞进首屏 bundle。
const Login = lazy(() => import('./pages/Login'));
const Home = lazy(() => import('./pages/Home'));
const Planner = lazy(() => import('./pages/Planner'));
const Trip = lazy(() => import('./pages/Trip'));
const Profile = lazy(() => import('./pages/Profile'));
const Expenses = lazy(() => import('./pages/Expenses'));
const Memos = lazy(() => import('./pages/Memos'));
const Office = lazy(() => import('./pages/Office'));
const Learning = lazy(() => import('./pages/Learning'));
const Health = lazy(() => import('./pages/Health'));
const Assistant = lazy(() => import('./pages/Assistant'));
const Travel = lazy(() => import('./pages/Travel'));
const Workbench = lazy(() => import('./pages/Workbench'));

function Protected({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function RouteLoading() {
  return (
    <div className="route-loading">
      <Spin size="large" />
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <Suspense fallback={<RouteLoading />}>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<Protected><Home /></Protected>} />
          <Route path="/planner" element={<Protected><Planner /></Protected>} />
          <Route path="/trip/:id" element={<Protected><Trip /></Protected>} />
          <Route path="/expenses" element={<Protected><Expenses /></Protected>} />
          <Route path="/memos" element={<Protected><Memos /></Protected>} />
          <Route path="/office" element={<Protected><Office /></Protected>} />
          <Route path="/learning" element={<Protected><Learning /></Protected>} />
          <Route path="/health" element={<Protected><Health /></Protected>} />
          {/* 日程已并入旅行规划（?tab=schedule），旧链接继续可用 */}
          <Route path="/schedule" element={<Navigate to="/travel?tab=schedule" replace />} />
          <Route path="/assistant" element={<Protected><Assistant /></Protected>} />
          <Route path="/travel" element={<Protected><Travel /></Protected>} />
          {/* 足迹已并入旅行规划（?tab=footprint），旧链接继续可用 */}
          <Route path="/footprint" element={<Navigate to="/travel?tab=footprint" replace />} />
          <Route path="/workbench" element={<Protected><Workbench /></Protected>} />
          <Route path="/profile" element={<Protected><Profile /></Protected>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
