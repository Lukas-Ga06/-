import { useEffect, useState } from 'react';
import { App, Button, Tag } from 'antd';
import { LogoutOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { systemStatus, type SystemStatus } from '../../api/assistant';
import { signOut } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';
import { useThemeStore } from '../../store/themeStore';
import { useTripStore } from '../../store/tripStore';
import AppShell from '../../components/AppShell';

export default function Profile() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const user = useAuthStore((s) => s.user);
  const trips = useTripStore((s) => s.trips);
  const loadTrips = useTripStore((s) => s.loadTrips);
  const themeMode = useThemeStore((s) => s.mode);
  const toggleTheme = useThemeStore((s) => s.toggle);
  const [status, setStatus] = useState<SystemStatus | null>(null);

  useEffect(() => {
    loadTrips().catch(() => undefined);
    systemStatus()
      .then(setStatus)
      .catch(() => undefined);
  }, []);

  const confirmed = trips.filter((t) => t.status === 'CONFIRMED').length;

  return (
    <AppShell active="profile">
      <div className="profile-page">
        <div className="profile-card fade-up">
          <div className="profile-head">
            <div className="profile-avatar">{(user?.nickname || user?.username || '旅')[0]}</div>
            <div>
              <div style={{ fontSize: 19, fontWeight: 800 }}>{user?.nickname ?? user?.username}</div>
              <div style={{ color: 'var(--ink-3)', fontSize: 12.5 }}>@{user?.username}</div>
            </div>
          </div>

          <div className="stat-grid">
            <div className="stat-box">
              <div className="sb-num">{trips.length}</div>
              <div className="sb-label">全部行程</div>
            </div>
            <div className="stat-box">
              <div className="sb-num" style={{ color: 'var(--success)' }}>{confirmed}</div>
              <div className="sb-label">已确认行程</div>
            </div>
          </div>

          <div
            style={{
              background: 'var(--grad-card)',
              border: '1px solid var(--line)',
              borderRadius: 'var(--radius-md)',
              padding: 16,
              marginBottom: 16,
              fontSize: 12.5,
              color: 'var(--ink-2)',
              lineHeight: 1.9,
            }}
          >
            <b style={{ color: 'var(--ink)' }}>运行能力</b>
            {status ? (
              <>
                <br />
                大模型：{status.llmConfigured ? <Tag color="green">已接入 {status.llmModel}</Tag> : <Tag>规则引擎</Tag>}
                {status.llmHint}
                {status.llmQuotaLimit != null && (
                  <>
                    <br />
                    今日 AI 调用：
                    <Tag color={status.llmQuotaUsed! >= status.llmQuotaLimit ? 'red' : 'blue'}>
                      {status.llmQuotaUsed} / {status.llmQuotaLimit}
                    </Tag>
                    超限后自动降级规则引擎，次日恢复
                  </>
                )}
                <br />
                地图定位：{status.amapConfigured ? <Tag color="green">高德</Tag> : <Tag>OSM</Tag>}
                {status.geoHint}
                <br />
                时区：{status.timezone}
              </>
            ) : (
              <>
                <br />
                正在检测…
              </>
            )}
          </div>

          {/* 外观：移动端（底部标签栏之外的）第二处主题入口，桌面侧栏底部也有一个 */}
          <div
            style={{
              background: 'var(--grad-card)',
              border: '1px solid var(--line)',
              borderRadius: 'var(--radius-md)',
              padding: 16,
              marginBottom: 16,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
              <div style={{ minWidth: 0 }}>
                <b style={{ color: 'var(--ink)', fontSize: 12.5 }}>外观</b>
                <div style={{ marginTop: 4, fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.6 }}>
                  当前：{themeMode === 'dark' ? '暗色' : '浅色'} · 选择会保存在本机，首次进入跟随系统
                </div>
              </div>
              <Button
                aria-label={themeMode === 'dark' ? '切换到浅色模式' : '切换到暗色模式'}
                data-testid="theme-toggle-profile"
                onClick={toggleTheme}
              >
                {themeMode === 'dark' ? '☀️ 浅色' : '🌙 暗色'}
              </Button>
            </div>
          </div>

          <div
            style={{
              background: 'var(--grad-card)',
              border: '1px solid var(--line)',
              borderRadius: 'var(--radius-md)',
              padding: 16,
              fontSize: 12.5,
              color: 'var(--ink-2)',
              lineHeight: 2,
            }}
          >
            <b style={{ color: 'var(--ink)' }}>关于本系统</b>
            <br />
            · Agent 引擎：Intent / Planner / Executor / Reflect 四阶段 + Human-in-the-loop 确认
            <br />
            · 一期工具：天气 / 路线 / 景点 / 酒店 / 餐厅 / 预算 / 行程读写 / 撤销 等 10 个
            <br />
            · RAG 知识库：旅游攻略向量化检索；LLM 可在配置中接入 OpenAI-compatible 模型
          </div>

          <div style={{ marginTop: 22, display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <Button onClick={() => navigate('/')}>返回首页</Button>
            <Button
              type="primary"
              danger
              icon={<LogoutOutlined />}
              onClick={() => {
                signOut();
                navigate('/login');
                message.success('已退出登录');
              }}
            >
              退出登录
            </Button>
          </div>
        </div>
      </div>
    </AppShell>
  );
}
