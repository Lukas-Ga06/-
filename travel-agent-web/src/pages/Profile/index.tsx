import { useEffect, useState } from 'react';
import { App, Button, Checkbox, Input, Modal, Tag } from 'antd';
import {
  DeleteOutlined,
  DownloadOutlined,
  ExclamationCircleOutlined,
  LogoutOutlined,
  MoonOutlined,
  SunOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { systemStatus, type SystemStatus } from '../../api/assistant';
import { deleteAccountApi, exportAccountDataApi, signOut } from '../../api/auth';
import { getErrorStatus } from '../../api/client';
import { useAuthStore } from '../../store/authStore';
import { useThemeStore } from '../../store/themeStore';
import { useTripStore } from '../../store/tripStore';
import AppShell from '../../components/AppShell';
import './profile.css';

export default function Profile() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const user = useAuthStore((s) => s.user);
  const trips = useTripStore((s) => s.trips);
  const loadTrips = useTripStore((s) => s.loadTrips);
  const themeMode = useThemeStore((s) => s.mode);
  const toggleTheme = useThemeStore((s) => s.toggle);
  const [status, setStatus] = useState<SystemStatus | null>(null);

  // 注销账号：密码二次确认；任何情况下都不能因为 401 误触全局登出
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deletePassword, setDeletePassword] = useState('');
  const [deleteConfirmed, setDeleteConfirmed] = useState(false);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // 导出数据：只读动作，与注销各自维护 busy/错误，互不禁用
  const [exportBusy, setExportBusy] = useState(false);

  useEffect(() => {
    loadTrips().catch(() => undefined);
    systemStatus()
      .then(setStatus)
      .catch(() => undefined);
  }, [loadTrips]);

  const openDelete = () => {
    setDeletePassword('');
    setDeleteConfirmed(false);
    setDeleteError(null);
    setDeleteOpen(true);
  };

  const closeDelete = () => {
    if (deleteBusy) return;
    setDeleteOpen(false);
    setDeleteError(null);
    setDeletePassword('');
    setDeleteConfirmed(false);
  };

  const canDelete = deletePassword.trim().length > 0 && deleteConfirmed && !deleteBusy;

  const submitDelete = async () => {
    if (!deletePassword.trim()) {
      setDeleteError('请输入当前密码');
      return;
    }
    if (!deleteConfirmed) {
      setDeleteError('请先勾选确认，注销后无法恢复');
      return;
    }
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      await deleteAccountApi(deletePassword);
      // Cookie 已由后端清掉，这里主动清本地登录态，不能只依赖服务端登出调用
      useAuthStore.getState().logout();
      message.success('账号已注销');
      navigate('/login', { replace: true });
    } catch (error: any) {
      const status = getErrorStatus(error);
      // 密码错误也是 401，但它只表示本次确认失败：保持登录态，表单内联提示
      setDeleteError(
        status === 401 ? '密码错误，请重新输入。' : error?.message || '注销失败，请稍后重试。',
      );
    } finally {
      setDeleteBusy(false);
    }
  };

  /**
   * 导出本人数据：拿到 JSON 后在浏览器侧生成文件下载，服务端不落盘。
   * 失败只提示、不改变登录态——尤其是 429（限流桶，默认 5 分钟 3 次）不能当成登录失效。
   */
  const exportData = async () => {
    setExportBusy(true);
    try {
      const data = await exportAccountDataApi();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `travel-agent-${user?.username ?? 'account'}-${new Date()
        .toISOString()
        .slice(0, 10)}.json`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      message.success('数据已导出');
    } catch (error: any) {
      const status = getErrorStatus(error);
      message.error(
        status === 429 ? '导出太频繁，请稍后再试。' : error?.message || '导出失败，请稍后重试。',
      );
    } finally {
      setExportBusy(false);
    }
  };

  const confirmed = trips.filter((t) => t.status === 'CONFIRMED').length;

  return (
    <AppShell active="profile">
      <div className="profile-page">
        <div className="profile-card fade-up">
          <div className="profile-head">
            <div className="profile-avatar">{(user?.nickname || user?.username || '旅')[0]}</div>
            <div>
              <div style={{ fontSize: 19, fontWeight: 800 }}>
                {user?.nickname ?? user?.username}
              </div>
              <div style={{ color: 'var(--ink-3)', fontSize: 12.5 }}>@{user?.username}</div>
            </div>
          </div>

          <div className="stat-grid">
            <div className="stat-box">
              <div className="sb-num">{trips.length}</div>
              <div className="sb-label">全部行程</div>
            </div>
            <div className="stat-box">
              <div className="sb-num" style={{ color: 'var(--success)' }}>
                {confirmed}
              </div>
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
                大模型：
                {status.llmConfigured ? (
                  <Tag color="green">已接入 {status.llmModel}</Tag>
                ) : (
                  <Tag>规则引擎</Tag>
                )}
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
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
              }}
            >
              <div style={{ minWidth: 0 }}>
                <b style={{ color: 'var(--ink)', fontSize: 12.5 }}>外观</b>
                <div
                  style={{ marginTop: 4, fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.6 }}
                >
                  当前：{themeMode === 'dark' ? '暗色' : '浅色'} ·
                  选择会保存在本机，首次进入跟随系统
                </div>
              </div>
              <Button
                aria-label={themeMode === 'dark' ? '切换到浅色模式' : '切换到暗色模式'}
                data-testid="theme-toggle-profile"
                onClick={toggleTheme}
              >
                {themeMode === 'dark' ? <SunOutlined /> : <MoonOutlined />}{' '}
                {themeMode === 'dark' ? '浅色' : '暗色'}
              </Button>
            </div>
          </div>

          <div
            style={{
              background: 'var(--grad-card)',
              border: '1px solid var(--line)',
              borderRadius: 'var(--radius-md)',
              padding: 16,
              marginBottom: 16,
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
              }}
            >
              <div style={{ minWidth: 0 }}>
                <b style={{ color: 'var(--ink)', fontSize: 12.5 }}>诊断</b>
                <div
                  style={{ marginTop: 4, fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.6 }}
                >
                  任务 DAG、工具调用与内部错误只在这里查看，不再占用主导航
                </div>
              </div>
              <Button
                data-testid="open-diagnostics"
                onClick={() => navigate('/settings/diagnostics')}
              >
                运行诊断
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
            <br />· RAG 知识库：旅游攻略向量化检索；LLM 可在配置中接入 OpenAI-compatible 模型
          </div>

          {/* 数据可携带：只读导出，刻意与「危险区」分开，避免被误读成删除类操作 */}
          <div
            style={{
              background: 'var(--grad-card)',
              border: '1px solid var(--line)',
              borderRadius: 'var(--radius-md)',
              padding: 16,
              marginBottom: 16,
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
              }}
            >
              <div style={{ minWidth: 0 }}>
                <b style={{ color: 'var(--ink)', fontSize: 12.5 }}>
                  <DownloadOutlined aria-hidden="true" /> 导出我的数据
                </b>
                <div
                  style={{ marginTop: 4, fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.6 }}
                >
                  下载一份 JSON：账号资料、行程、记账、备忘、日程、会话与你上传的知识库文档；
                  照片只给清单与访问地址，不含图片文件。
                </div>
              </div>
              <Button
                icon={<DownloadOutlined />}
                loading={exportBusy}
                onClick={exportData}
                data-testid="export-account-data"
              >
                导出数据
              </Button>
            </div>
          </div>

          {/* 危险区：低频、不可恢复的动作单独成块，与日常设置用留白隔开 */}
          <section className="profile-danger" aria-labelledby="profile-danger-title">
            <div className="profile-danger-copy">
              <b id="profile-danger-title">
                <ExclamationCircleOutlined aria-hidden="true" /> 危险区 · 注销账号
              </b>
              <p>
                注销后会永久删除你的账号、全部行程、照片、会话以及由它们派生的数据，且不可恢复。
              </p>
            </div>
            <Button
              danger
              icon={<DeleteOutlined />}
              onClick={openDelete}
              aria-label="注销账号"
              data-testid="open-delete-account"
            >
              注销账号
            </Button>
          </section>

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

      <Modal
        title="注销账号"
        open={deleteOpen}
        onCancel={closeDelete}
        width={460}
        maskClosable={!deleteBusy}
        keyboard={!deleteBusy}
        destroyOnHidden
        footer={[
          <Button key="cancel" onClick={closeDelete} disabled={deleteBusy}>
            取消
          </Button>,
          <Button
            key="delete"
            type="primary"
            danger
            loading={deleteBusy}
            disabled={!canDelete}
            onClick={submitDelete}
            data-testid="confirm-delete-account"
          >
            永久注销
          </Button>,
        ]}
      >
        <div className="profile-delete-form">
          <p className="profile-delete-warning">
            当前账号 <b>@{user?.username}</b>{' '}
            将被永久删除，包括行程、照片、会话与派生数据。此操作不可恢复。
          </p>
          <div className="profile-delete-field">
            <label htmlFor="delete-account-password">当前密码</label>
            <Input.Password
              id="delete-account-password"
              value={deletePassword}
              onChange={(event) => {
                setDeletePassword(event.target.value);
                setDeleteError(null);
              }}
              placeholder="输入当前密码确认身份"
              autoComplete="current-password"
              status={deleteError ? 'error' : undefined}
              aria-label="当前密码"
            />
          </div>
          <Checkbox
            checked={deleteConfirmed}
            onChange={(event) => {
              setDeleteConfirmed(event.target.checked);
              setDeleteError(null);
            }}
          >
            我已了解：注销后账号与全部数据不可恢复
          </Checkbox>
          {deleteError && (
            <div className="profile-delete-error" role="alert" data-testid="delete-account-error">
              {deleteError}
            </div>
          )}
        </div>
      </Modal>
    </AppShell>
  );
}
