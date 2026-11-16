import { createContext, useContext, useEffect, useState } from 'react';
import { Badge, Button } from 'antd';
import { LogoutOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { signOut } from '../api/auth';
import { useThemeStore } from '../store/themeStore';
import { getMemoOpenCount } from '../api/memo';
import ReminderBell from './ReminderBell';

const GROUPS: { section: string; items: { key: string; label: string; path: string; icon: string }[] }[] = [
  {
    section: '总览',
    items: [
      { key: 'home', label: '首页', path: '/', icon: '🏠' },
      { key: 'travel', label: '旅行规划', path: '/travel', icon: '🧭' },
    ],
  },
  {
    section: '生活',
    items: [
      { key: 'expenses', label: '记账', path: '/expenses', icon: '💰' },
      { key: 'memos', label: '备忘', path: '/memos', icon: '📝' },
      { key: 'health', label: '健康', path: '/health', icon: '❤️' },
    ],
  },
  {
    section: '智能',
    items: [
      { key: 'assistant', label: 'AI 助理', path: '/assistant', icon: '🤖' },
      { key: 'workbench', label: '工作台', path: '/workbench', icon: '🧰' },
    ],
  },
];

/** 窄屏底部标签栏：只放最高频的四个入口 */
const TABS: { key: NavModule; label: string; path: string; icon: string }[] = [
  { key: 'home', label: '首页', path: '/', icon: '🏠' },
  { key: 'travel', label: '旅行', path: '/travel', icon: '🧭' },
  { key: 'assistant', label: '助手', path: '/assistant', icon: '🤖' },
  { key: 'profile', label: '我的', path: '/profile', icon: '👤' },
];

export type NavModule =
  | 'home' | 'schedule' | 'travel' | 'footprint' | 'expenses' | 'memos' | 'health'
  | 'assistant' | 'office' | 'learning' | 'workbench' | 'profile';

/** 嵌套在已有外壳内时不再重复渲染侧栏（例如工作台用 Tabs 复用办公/学习页） */
const InsideShell = createContext(false);

/** 个人助理应用外壳：左侧固定侧栏（窄屏收成图标栏 / 底部标签栏）+ 右侧内容区 */
export default function AppShell({ active, children }: { active: NavModule; children: React.ReactNode }) {
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

  return (
    <InsideShell.Provider value={true}>
      <div className="app-shell">
        <aside className="app-sidebar">
        <div className="sb-brand" data-testid="app-shell-brand" onClick={() => navigate('/')} style={{ cursor: 'pointer' }}>
          <span className="hn-logo">🧠</span>
          <span className="sb-brand-text">AI 个人助理</span>
        </div>
        <nav className="sb-modules">
          {GROUPS.map((g) => (
            <div key={g.section}>
              <div className="sb-section">{g.section}</div>
              {g.items.map((m) => (
                <button
                  key={m.key}
                  className={`sb-link ${active === m.key ? 'active' : ''}`}
                  onClick={() => navigate(m.path)}
                  title={m.label}
                >
                  {m.key === 'memos' && memoOpen > 0 ? (
                    <Badge count={memoOpen} size="small" overflowCount={99} offset={[-3, 3]}>
                      <span>{m.icon}</span>
                    </Badge>
                  ) : (
                    <span>{m.icon}</span>
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
            className={`sb-link ${active === 'profile' ? 'active' : ''}`}
            onClick={() => navigate('/profile')}
            title="设置"
          >
            <span>⚙️</span>
            <span className="sb-label">设置</span>
          </button>
          <div className="sb-user">
            <span className="sb-avatar">{(user?.nickname || user?.username || '旅')[0]}</span>
            <span
              className="sb-user-name"
              style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}
            >
              {user?.nickname ?? user?.username}
            </span>
            <Button
              type="text"
              size="small"
              danger
              icon={<LogoutOutlined />}
              title="退出登录"
              onClick={() => {
                signOut();
                navigate('/login');
              }}
            />
            <Button
              type="text"
              size="small"
              title={themeMode === 'dark' ? '切换到浅色' : '切换到暗色'}
              aria-label={themeMode === 'dark' ? '切换到浅色模式' : '切换到暗色模式'}
              data-testid="theme-toggle-sidebar"
              onClick={toggleTheme}
            >
              {themeMode === 'dark' ? '☀️' : '🌙'}
            </Button>
          </div>
        </div>
        </aside>
        <main className="app-content">{children}</main>
        <nav className="app-tabbar">
          {TABS.map((t) => (
            <a
              key={t.key}
              className={active === t.key ? 'on' : ''}
              onClick={() => navigate(t.path)}
            >
              <span className="tb-icon">{t.icon}</span>
              <span>{t.label}</span>
            </a>
          ))}
          <a
            onClick={toggleTheme}
            role="button"
            title={themeMode === 'dark' ? '切换到浅色' : '切换到暗色'}
            aria-label={themeMode === 'dark' ? '切换到浅色模式' : '切换到暗色模式'}
            data-testid="theme-toggle-tabbar"
          >
            <span className="tb-icon">{themeMode === 'dark' ? '☀️' : '🌙'}</span>
            <span>主题</span>
          </a>
        </nav>
      </div>
    </InsideShell.Provider>
  );
}
