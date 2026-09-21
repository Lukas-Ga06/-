import { Button } from 'antd';
import { LogoutOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';

const GROUPS: { section: string; items: { key: string; label: string; path: string; icon: string }[] }[] = [
  {
    section: '生活',
    items: [
      { key: 'home', label: '总览', path: '/', icon: '🏠' },
      { key: 'travel', label: '旅行', path: '/travel', icon: '🗺️' },
      { key: 'schedule', label: '日程', path: '/schedule', icon: '📅' },
      { key: 'expenses', label: '记账', path: '/expenses', icon: '💰' },
      { key: 'memos', label: '备忘', path: '/memos', icon: '📝' },
      { key: 'health', label: '健康', path: '/health', icon: '❤️' },
    ],
  },
  {
    section: 'AI 工作台',
    items: [
      { key: 'assistant', label: 'AI 助理', path: '/assistant', icon: '🤖' },
      { key: 'office', label: '自动化', path: '/office', icon: '⚡' },
      { key: 'learning', label: '学习', path: '/learning', icon: '📚' },
    ],
  },
];

export type NavModule =
  | 'home' | 'schedule' | 'travel' | 'footprint' | 'expenses' | 'memos' | 'health'
  | 'assistant' | 'office' | 'learning' | 'profile';

/** 个人助理应用外壳：左侧悬浮玻璃边栏 + 右侧内容区 */
export default function AppShell({ active, children }: { active: NavModule; children: React.ReactNode }) {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <div className="sb-brand" onClick={() => navigate('/')} style={{ cursor: 'pointer' }}>
          <span className="hn-logo">🧠</span> AI 个人助理
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
                >
                  <span>{m.icon}</span> {m.label}
                </button>
              ))}
            </div>
          ))}
        </nav>
        <div className="sb-footer">
          <button className={`sb-link ${active === 'profile' ? 'active' : ''}`} onClick={() => navigate('/profile')}>
            <span>⚙️</span> 设置
          </button>
          <div className="sb-user">
            <span className="sb-avatar">{(user?.nickname || user?.username || '旅')[0]}</span>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {user?.nickname ?? user?.username}
            </span>
            <Button
              type="text"
              size="small"
              danger
              icon={<LogoutOutlined />}
              title="退出登录"
              onClick={() => {
                logout();
                navigate('/login');
              }}
            />
          </div>
        </div>
      </aside>
      <main className="app-content">{children}</main>
    </div>
  );
}
