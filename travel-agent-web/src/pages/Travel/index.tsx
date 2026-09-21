import { useEffect, useState } from 'react';
import { App, Button, Empty, Input, Popconfirm } from 'antd';
import { DeleteOutlined, RobotOutlined, SearchOutlined, ThunderboltOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { deleteTrip, listTrips } from '../../api/trip';
import { useAuthStore } from '../../store/authStore';
import { useTripStore } from '../../store/tripStore';
import VoiceButton from '../../components/VoiceButton';
import AppShell from '../../components/AppShell';

const SUGGESTIONS = [
  '上海三日游，预算3000元，酒店离地铁近一点',
  '周末去杭州玩，帮我规划一下',
  '北京亲子游，玩三天，预算6000元',
];

const FEATURES = [
  { icon: <RobotOutlined />, cls: 'blue', title: 'Agent 智能规划', desc: '四阶段引擎生成含酒店、景点、餐饮的多日行程。' },
  { icon: <ThunderboltOutlined />, cls: 'amber', title: '行中动态调整', desc: '「下雨换掉户外景点」，Diff 对比一键确认，支持撤销。' },
  { icon: <SearchOutlined />, cls: 'indigo', title: '3D 地图与知识库', desc: 'MapLibre 3D 足迹与行程路线；攻略知识向量检索。' },
];

/** 旅行规划模块 */
export default function Travel() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const trips = useTripStore((s) => s.trips);
  const tripsLoading = useTripStore((s) => s.tripsLoading);
  const loadTrips = useTripStore((s) => s.loadTrips);
  const user = useAuthStore((s) => s.user);
  const [text, setText] = useState('');

  useEffect(() => {
    loadTrips().catch((e) => message.error(e.message));
  }, []);

  const start = (value?: string) => {
    const prompt = (value ?? text).trim();
    if (!prompt) {
      message.warning('先告诉我你想去哪里吧');
      return;
    }
    navigate(`/planner?prompt=${encodeURIComponent(prompt)}`);
  };

  const remove = async (id: number) => {
    try {
      await deleteTrip(id);
      message.success('已删除');
      loadTrips();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  return (
    <AppShell active="travel">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>🧭 旅行规划</h1>
            <div className="dg-date">
              {user ? `${user.nickname ?? user.username} 的旅行空间 · ` : ''}
              一句话生成行程，行中随时调整
            </div>
          </div>
        </div>

        <div className="search-card fade-up">
          <div className="search-row">
            <Input
              size="large"
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="例如：帮我安排一个三天的城市旅行，预算3000元，酒店离地铁近一点"
              onPressEnter={() => start()}
              suffix={<span style={{ color: '#8e8e93', fontSize: 12 }}>Enter 发送</span>}
            />
            <VoiceButton onText={(t) => setText(t)} />
            <Button size="large" type="primary" icon={<RobotOutlined />} onClick={() => start()}>
              开始规划
            </Button>
          </div>
          <div className="chip-row">
            <span className="chip-label">试试：</span>
            {SUGGESTIONS.map((s) => (
              <span key={s} className="suggest-chip" onClick={() => setText(s)}>
                {s}
              </span>
            ))}
          </div>
        </div>

        <div className="feature-grid">
          {FEATURES.map((f) => (
            <div className="feature-card" key={f.title}>
              <div className={`fc-icon ${f.cls}`}>{f.icon}</div>
              <div>
                <b>{f.title}</b>
                <span>{f.desc}</span>
              </div>
            </div>
          ))}
        </div>

        <div className="section-head">
          <h3>我的行程</h3>
          <Button type="text" icon={<UserOutlined />} onClick={() => navigate('/profile')}>
            个人资料
          </Button>
        </div>

        {trips.length === 0 && !tripsLoading ? (
          <div className="empty-trips">
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有行程，输入一个旅行想法让 AI 帮你规划吧" />
          </div>
        ) : (
          <div className="inset-list fade-up">
            {trips.map((t) => (
              <div key={t.id} className="trip-row" onClick={() => navigate(`/trip/${t.id}`)}>
                <div
                  className="tr-icon"
                  style={{ background: 'var(--grad-primary)' }}
                >
                  {(t.destination || '旅')[0]}
                </div>
                <div className="tr-main">
                  <div className="tr-title">
                    {t.name}
                    {t.status === 'CONFIRMED' ? (
                      <span className="mini-tag outdoor">已确认</span>
                    ) : (
                      <span className="mini-tag indoor">规划中</span>
                    )}
                  </div>
                  <div className="tr-sub">
                    {t.destination ?? '未设置'} · {dayjs(t.startDate).format('MM月DD日')} -{' '}
                    {dayjs(t.endDate).format('MM月DD日')}
                  </div>
                </div>
                <div className="tr-right">
                  <span className="tr-budget">¥{t.budget}</span>
                  <span className="tr-delete" onClick={(e) => e.stopPropagation()}>
                    <Popconfirm title="确定删除该行程？" onConfirm={() => remove(t.id)}>
                      <Button type="text" danger size="small" icon={<DeleteOutlined />} />
                    </Popconfirm>
                  </span>
                  <span className="tr-chevron">›</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </AppShell>
  );
}
