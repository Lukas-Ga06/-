import { Button } from 'antd';
import { LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { signOut } from '../api/auth';

const MODULES = [
  { key: 'home', label: '🏠 总览', path: '/' },
  { key: 'travel', label: '🧭 旅行', path: '/' },
  { key: 'expenses', label: '💰 记账', path: '/expenses' },
  { key: 'memos', label: '📝 备忘', path: '/memos' },
  { key: 'office', label: '🏢 办公', path: '/office' },
  { key: 'learning', label: '📚 学习', path: '/learning' },
  { key: 'health', label: '❤️ 健康', path: '/health' },
] as const;

export type NavModule = 'home' | 'travel' | 'expenses' | 'memos' | 'office' | 'learning' | 'health';

/** 个人助理顶部导航：模块切换 + 用户操作 */
export default function AssistantNav({ active }: { active: NavModule }) {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  return (
    <header className="home-nav ios-nav">
      <span className="hn-brand" onClick={() => navigate('/')} style={{ cursor: 'pointer' }}>
        <span className="hn-logo">🧭</span> AI 个人助理
      </span>
      <nav className="hn-modules">
        {MODULES.map((m) => (
          <button
            key={m.key}
            className={`hn-link ${active === m.key ? 'active' : ''}`}
            onClick={() => navigate(m.path)}
          >
            {m.label}
          </button>
        ))}
      </nav>
      <span className="hn-spacer" />
      {user && <span className="hn-user">👋 {user.nickname ?? user.username}</span>}
      <Button type="text" icon={<UserOutlined />} onClick={() => navigate('/profile')} />
      <Button
        type="text"
        icon={<LogoutOutlined />}
        onClick={() => {
          signOut();
          navigate('/login');
        }}
      />
    </header>
  );
}
