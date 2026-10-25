import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { lazy, Suspense, useEffect } from 'react';
import type { ReactNode } from 'react';
import { Spin } from 'antd';
import { useAuthStore } from './store/authStore';
import { meApi } from './api/auth';
import PageTransition from './components/PageTransition';
import { ToastContainer } from './components/Toast';
import ErrorBoundary from './components/ErrorBoundary';

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
const UIDemo = lazy(() => import('./pages/UIDemo'));

function Protected({ children }: { children: ReactNode }) {
  const user = useAuthStore((s) => s.user);
  const authChecked = useAuthStore((s) => s.authChecked);
  // 启动恢复进行中：等 /auth/me 探活完成再判断，避免误判成未登录
  if (!authChecked) {
    return (
      <div className="route-loading">
        <Spin size="large" />
      </div>
    );
  }
  if (!user) {
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

/** 包裹路由切换的过渡动画 */
function AnimatedRoutes() {
  const location = useLocation();
  return (
    // key 按 pathname：切换页面时重置错误边界，上一页的渲染异常不会锁死下一页
    <ErrorBoundary key={location.pathname}>
      <Suspense fallback={<RouteLoading />}>
        <PageTransition variant="slide" duration={280}>
          <Routes location={location}>
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
            <Route path="/ui-demo" element={<Protected><UIDemo /></Protected>} />
          </Routes>
        </PageTransition>
      </Suspense>
    </ErrorBoundary>
  );
}

export default function App() {
  const setUser = useAuthStore((s) => s.setUser);
  const markAuthChecked = useAuthStore((s) => s.markAuthChecked);

  // 登录态恢复：token 不再持久化在 localStorage，刷新后凭 httpOnly Cookie 换回用户资料；
  // 未登录时 401 走拦截器（不跳转），由 Protected 决定去留
  useEffect(() => {
    meApi()
      .then((resp) => {
        setUser({ id: resp.id, username: resp.username, nickname: resp.nickname });
      })
      .catch(() => {})
      .finally(() => markAuthChecked());
  }, [setUser, markAuthChecked]);

  return (
    <BrowserRouter>
      <AnimatedRoutes />
      <ToastContainer />
    </BrowserRouter>
  );
}
