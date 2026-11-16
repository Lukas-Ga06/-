import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { lazy, Suspense, useEffect } from 'react';
import type { ReactNode } from 'react';
import { App as AntApp, Button, Result, Spin } from 'antd';
import { useAuthStore } from './store/authStore';
import { meApi } from './api/auth';
import {
  buildRateLimitNotice,
  getErrorRetryAfter,
  getErrorStatus,
  isAuthExpired,
  shouldRetryMe,
} from './api/client';
import PageTransition from './components/PageTransition';
import { ToastContainer } from './components/Toast';
import ErrorBoundary from './components/ErrorBoundary';
import OfflineBanner from './components/OfflineBanner';

/** /auth/me 被限流/服务端临时故障时的退避间隔；401 不重试 */
const ME_RETRY_DELAYS_MS = [1000, 2000];

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

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

/**
 * 登录态恢复「暂时失败」时的兜底页：429 限流 / 5xx / 断网都不代表用户没登录，
 * 所以既不跳 /login 也不清登录态，只提示重试。
 */
function SessionRecoveryFailed({ reason }: { reason: string }) {
  return (
    <div className="route-loading" data-testid="session-recovery-failed">
      <Result
        status="warning"
        title={reason}
        subTitle="登录状态已保留，没有退出登录；确认网络或稍等片刻后重试即可。"
        extra={
          <Button type="primary" onClick={() => window.location.reload()}>
            重新加载
          </Button>
        }
      />
    </div>
  );
}

function Protected({ children }: { children: ReactNode }) {
  const user = useAuthStore((s) => s.user);
  const authChecked = useAuthStore((s) => s.authChecked);
  const sessionError = useAuthStore((s) => s.sessionError);
  // 启动恢复进行中：等 /auth/me 探活完成再判断，避免误判成未登录
  if (!authChecked) {
    return (
      <div className="route-loading">
        <Spin size="large" />
      </div>
    );
  }
  if (!user) {
    // 只有「确实没登录（401）」才跳登录页；429/5xx/网络错误保留登录态并提示重试
    if (sessionError) {
      return <SessionRecoveryFailed reason={sessionError} />;
    }
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
  const { message } = AntApp.useApp();

  // 登录态恢复：token 不再持久化在 localStorage，刷新后凭 httpOnly Cookie 换回用户资料。
  // 按状态码区分语义（B8）：401 = 确实未登录 → 清登录态，交给 Protected 跳登录页；
  // 429/5xx/网络错误 = 登录态可能仍然有效 → 保留登录态 + 退避重试（1s、2s），
  // 重试仍失败则提示「稍后重试」，绝不把用户静默踢回登录页。
  useEffect(() => {
    let cancelled = false;

    const restore = async (): Promise<void> => {
      for (let attempt = 0; ; attempt += 1) {
        try {
          const resp = await meApi();
          if (cancelled) return;
          setUser({ id: resp.id, username: resp.username, nickname: resp.nickname });
          return;
        } catch (err) {
          if (cancelled) return;
          const status = getErrorStatus(err);
          if (isAuthExpired(status)) {
            // 401：Cookie 确实失效，清掉本地登录态（Protected 随后跳 /login）
            useAuthStore.getState().logout();
            return;
          }
          const delay = ME_RETRY_DELAYS_MS[attempt];
          if (shouldRetryMe(status) && delay !== undefined) {
            await sleep(delay);
            if (cancelled) return;
            continue;
          }
          const notice =
            status === 429
              ? buildRateLimitNotice(getErrorRetryAfter(err))
              : '登录状态校验失败：网络或服务暂时不可用';
          useAuthStore.getState().setSessionError(notice);
          message.warning(notice);
          return;
        }
      }
    };

    restore().finally(() => {
      if (!cancelled) markAuthChecked();
    });

    return () => {
      cancelled = true;
    };
  }, [setUser, markAuthChecked, message]);

  return (
    <BrowserRouter>
      {/* 离线提示条放在路由外层：登录页/规划页（不套 AppShell）也能看到 */}
      <OfflineBanner />
      <AnimatedRoutes />
      <ToastContainer />
    </BrowserRouter>
  );
}
