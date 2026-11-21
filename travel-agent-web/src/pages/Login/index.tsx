import { useState } from 'react';
import { App, Button, Form, Input, Tabs, Typography } from 'antd';
import {
  CompassOutlined,
  GlobalOutlined,
  RobotOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { loginApi, registerApi } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';
import type { AuthResp } from '../../types/trip';
import './login.css';

const FEATURES = [
  { icon: <RobotOutlined />, cls: 'blue', title: 'Agent 智能规划', desc: '一句话生成多日完整行程' },
  {
    icon: <ThunderboltOutlined />,
    cls: 'green',
    title: '行中动态调整',
    desc: '「下雨了，换掉户外景点」',
  },
  {
    icon: <GlobalOutlined />,
    cls: 'indigo',
    title: '地图 + 时间轴',
    desc: '方案可视对比，一键确认',
  },
];

export default function Login() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);
  const [loading, setLoading] = useState(false);

  const handle = async (mode: 'login' | 'register', values: any) => {
    setLoading(true);
    try {
      const resp: AuthResp =
        mode === 'login'
          ? await loginApi(values.username, values.password)
          : await registerApi(values.username, values.password, values.nickname ?? '');
      setAuth(resp.token, { id: resp.id, username: resp.username, nickname: resp.nickname });
      message.success(mode === 'login' ? '欢迎回来！' : '注册成功，开始规划旅行吧！');
      navigate('/');
    } catch (e: any) {
      message.error(e.message || '操作失败');
    } finally {
      setLoading(false);
    }
  };

  const loginForm = (
    <Form
      layout="vertical"
      requiredMark={false}
      onFinish={(v) => handle('login', v)}
      initialValues={{ username: 'demo', password: 'demo123' }}
    >
      <Form.Item
        name="username"
        label="用户名"
        rules={[{ required: true, message: '请输入用户名' }]}
      >
        <Input
          size="large"
          placeholder="用户名"
          prefix={<span style={{ color: '#94a3b8' }}>👤</span>}
        />
      </Form.Item>
      <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
        <Input.Password size="large" placeholder="密码" />
      </Form.Item>
      <Button
        type="primary"
        htmlType="submit"
        block
        size="large"
        loading={loading}
        icon={<CompassOutlined />}
      >
        进入 AI 旅行助手
      </Button>
      <div className="login-tip">演示账号：demo / 密码：demo123</div>
    </Form>
  );

  const registerForm = (
    <Form layout="vertical" requiredMark={false} onFinish={(v) => handle('register', v)}>
      <Form.Item
        name="username"
        label="用户名"
        rules={[{ required: true, message: '请输入用户名' }]}
      >
        <Input size="large" placeholder="用户名" />
      </Form.Item>
      <Form.Item name="nickname" label="昵称">
        <Input size="large" placeholder="昵称（可选）" />
      </Form.Item>
      <Form.Item
        name="password"
        label="密码"
        rules={[{ required: true, min: 6, message: '密码至少6位' }]}
      >
        <Input.Password size="large" placeholder="密码（至少6位）" />
      </Form.Item>
      <Button type="primary" htmlType="submit" block size="large" loading={loading}>
        注册并登录
      </Button>
    </Form>
  );

  return (
    <div className="login-page">
      <div className="login-brand">
        <div>
          <h1>AI Travel Agent</h1>
          <div className="slogan">
            多模态智能旅行助手
            <br />
            行前规划 · 行中调整 · 一句话搞定
          </div>
        </div>
        <div className="feature-list">
          {FEATURES.map((f) => (
            <div className="feature-item" key={f.title}>
              <div className={`fi-icon ${f.cls}`}>{f.icon}</div>
              <div>
                <b>{f.title}</b>
                <span>{f.desc}</span>
              </div>
            </div>
          ))}
        </div>
        <div style={{ fontSize: 12, opacity: 0.7 }}>
          Spring Boot · Agent Engine · RAG · Tools · React
        </div>
      </div>
      <div className="login-form-panel">
        <div className="login-card fade-up">
          <div className="brand-mini">
            <div className="logo">🧭</div>
            <div>
              <b>AI Travel Agent</b>
              <span>多模态智能旅行助手</span>
            </div>
          </div>
          <Tabs
            centered
            items={[
              { key: 'login', label: '登录', children: loginForm },
              { key: 'register', label: '注册', children: registerForm },
            ]}
          />
          <Typography.Text
            type="secondary"
            style={{ display: 'block', textAlign: 'center', fontSize: 12 }}
          >
            登录即代表同意演示服务条款
          </Typography.Text>
        </div>
      </div>
    </div>
  );
}
