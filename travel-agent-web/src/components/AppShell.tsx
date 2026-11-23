import { createContext, useContext, useEffect, useState } from 'react';
import { Badge, Button, Tooltip } from 'antd';
import {
  BookOutlined,
  BugOutlined,
  CheckSquareOutlined,
  CompassOutlined,
  EnvironmentOutlined,
  HomeOutlined,
  LogoutOutlined,
  MessageOutlined,
  MoonOutlined,
  PlusOutlined,
  ReadOutlined,
  SettingOutlined,
  SunOutlined,
  UserOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { signOut } from '../api/auth';
import { useThemeStore } from '../store/themeStore';
import { getMemoOpenCount } from '../api/memo';
import ReminderBell from './ReminderBell';

/**
 * 信息架构（方案 §6.1）：
 * 一级导航只放用户任务，且按「总览 / 行程 / 智能 / 旅行资料」分组；
 * 「新建行程」是固定主动作，不占用分组；诊断默认不出现在导航里（见 DEV_ONLY 项）。
 */
const GROUPS: {
  section: string;
  items: { key: NavModule; label: string; path: string; icon: React.ReactNode }[];
}[] = [
  {
    section: '总览',
    items: [{ key: 'home', label: '首页', path: '/', icon: <HomeOutlined /> }],
  },
  {
    section: '行程',
    items: [{ key: 'travel', label: '我的行程', path: '/travel', icon: <CompassOutlined /> }],
  },
  {
    section: '智能',
    items: [{ key: 'assistant', label: 'AI 助手', path: '/assistant', icon: <MessageOutlined /> }],
  },
  {
    section: '旅行资料',
    items: [
      { key: 'knowledge', label: '攻略库', path: '/knowledge', icon: <ReadOutlined /> },
      { key: 'memos', label: '备忘', path: '/memos', icon: <CheckSquareOutlined /> },
      { key: 'expenses', label: '记账', path: '/expenses', icon: <WalletOutlined /> },
      { key: 'review', label: '回顾', path: '/review', icon: <BookOutlined /> },
      {
        key: 'footprint',
        label: '足迹与照片',
        path: '/travel?tab=footprint',
        icon: <EnvironmentOutlined />,
      },
    ],
  },
];

/** 窄屏底部标签栏：只放最高频的四个入口（主题切换已移入「我的」） */
const TABS: { key: NavModule; label: string; path: string; icon: React.ReactNode }[] = [
  { key: 'home', label: '首页', path: '/', icon: <HomeOutlined /> },
  { key: 'travel', label: '行程', path: '/travel', icon: <CompassOutlined /> },
  { key: 'assistant', label: '助手', path: '/assistant', icon: <MessageOutlined /> },
  { key: 'profile', label: '我的', path: '/profile', icon: <UserOutlined /> },
];

export type NavModule =
  | 'home'
  | 'planner'
  | 'schedule'
  | 'travel'
  | 'footprint'
  | 'expenses'
  | 'memos'
  | 'knowledge'
  | 'review'
  | 'assistant'
  | 'workbench'
  | 'diagnostics'
  | 'profile';

/** 嵌套在已有外壳内时不再重复渲染侧栏（例如旅行页用 Tabs 复用日程/足迹页） */
const InsideShell = createContext(false);

/** 智能旅行助手应用外壳：左侧固定侧栏（窄屏收成底部标签栏）+ 右侧内容区 */
export default function AppShell({
  active,
  children,
}: {
  active: NavModule;
  children: React.ReactNode;
}) {
  const insideShell = useContext(InsideShell);
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const themeMode = useThemeStore((s) => s.mode);
  const toggleTheme = useThemeStore((s) => s.toggle);

  // 备忘待办角标：AppShell 随页面切换重新挂载，每次导航拉一次最新计数（页内变化由备忘页顶栏实时展示）
  const [memoOpen, setMemoOpen] = useState(0);
  const userId = user?.id;
  useEffect(() => {
    if (!userId) return;
    let cancelled = false;
    getMemoOpenCount()
      .then((n) => {
        if (!cancelled) setMemoOpen(n);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [userId]);

  if (insideShell) {
    return <>{children}</>;
  }

  /** 运行诊断只对开发者暴露（方案 §6.2）：普通用户导航里看不到 DAG / 工具调用 / 异常类名 */
  const devOnly = import.meta.env.DEV;

  return (
    <InsideShell.Provider value={true}>
      <div className="app-shell">
        <aside className="app-sidebar">
          <button
            type="button"
            className="sb-brand"
            data-testid="app-shell-brand"
            onClick={() => navigate('/')}
            aria-label="回到首页"
          >
            <span className="hn-logo" aria-hidden="true">
              <CompassOutlined />
            </span>
            <span className="sb-brand-text">AI 智能旅行助手</span>
          </button>

          {/* 固定主动作：新建行程不再让用户在多个相似入口间选择 */}
          <Button
            type="primary"
            block
            icon={<PlusOutlined />}
            className="sb-new-trip"
            onClick={() => navigate('/planner')}
          >
            新建行程
          </Button>

          <nav className="sb-modules" aria-label="主导航">
            {GROUPS.map((g) => (
              <div key={g.section}>
                <div className="sb-section">{g.section}</div>
                {g.items.map((m) => (
                  <button
                    key={m.key}
                    type="button"
                    className={`sb-link ${active === m.key ? 'active' : ''}`}
                    onClick={() => navigate(m.path)}
                    title={m.label}
                    aria-current={active === m.key ? 'page' : undefined}
                  >
                    {m.key === 'memos' && memoOpen > 0 ? (
                      <Badge count={memoOpen} size="small" overflowCount={99} offset={[-3, 3]}>
                        <span className="sb-icon" aria-hidden="true">
                          {m.icon}
                        </span>
                      </Badge>
                    ) : (
                      <span className="sb-icon" aria-hidden="true">
                        {m.icon}
                      </span>
                    )}
                    <span className="sb-label">{m.label}</span>
                  </button>
                ))}
              </div>
            ))}
          </nav>

          <div className="sb-footer">
            <ReminderBell />
            <button
              type="button"
              className={`sb-link ${active === 'profile' ? 'active' : ''}`}
              onClick={() => navigate('/profile')}
              title="设置"
              aria-current={active === 'profile' ? 'page' : undefined}
            >
              <span className="sb-icon" aria-hidden="true">
                <SettingOutlined />
              </span>
              <span className="sb-label">设置</span>
            </button>
            {devOnly && (
              <button
                type="button"
                className={`sb-link ${active === 'diagnostics' ? 'active' : ''}`}
                onClick={() => navigate('/settings/diagnostics')}
                title="运行诊断"
                aria-current={active === 'diagnostics' ? 'page' : undefined}
              >
                <span className="sb-icon" aria-hidden="true">
                  <BugOutlined />
                </span>
                <span className="sb-label">运行诊断</span>
              </button>
            )}
            <div className="sb-user">
              <span className="sb-avatar">{(user?.nickname || user?.username || '旅')[0]}</span>
              <span className="sb-user-name">{user?.nickname ?? user?.username}</span>
              <Tooltip title="退出登录">
                <Button
                  type="text"
                  size="small"
                  danger
                  icon={<LogoutOutlined />}
                  aria-label="退出登录"
                  onClick={() => {
                    signOut();
                    navigate('/login');
                  }}
                />
              </Tooltip>
              <Tooltip title={themeMode === 'dark' ? '切换到浅色模式' : '切换到暗色模式'}>
                <Button
                  type="text"
                  size="small"
                  title={themeMode === 'dark' ? '切换到浅色' : '切换到暗色'}
                  aria-label={themeMode === 'dark' ? '切换到浅色模式' : '切换到暗色模式'}
                  data-testid="theme-toggle-sidebar"
                  icon={themeMode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
                  onClick={toggleTheme}
                />
              </Tooltip>
            </div>
          </div>
        </aside>
        <main className="app-content">{children}</main>
        <nav className="app-tabbar" aria-label="主导航">
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              className={`tb-item ${active === t.key ? 'on' : ''}`}
              aria-current={active === t.key ? 'page' : undefined}
              onClick={() => navigate(t.path)}
            >
              <span className="tb-icon" aria-hidden="true">
                {t.icon}
              </span>
              <span>{t.label}</span>
            </button>
          ))}
        </nav>
      </div>
    </InsideShell.Provider>
  );
}
