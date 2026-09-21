import { Tabs } from 'antd';
import { useSearchParams } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import Office from '../Office';
import Learning from '../Learning';

/**
 * AI 工作台：把「办公自动化」与「学习辅助」合并到一个页面用 Tab 切换，
 * 复用原有页面组件（它们的 AppShell 在外壳内会自动退化为纯内容）。
 */
export default function Workbench() {
  const [params, setParams] = useSearchParams();
  const activeKey = params.get('tab') === 'learning' ? 'learning' : 'office';

  return (
    <AppShell active="workbench">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>🧰 AI 工作台</h1>
            <div className="dg-date">
              办公自动化（邮件分类 · 会议纪要 · 日报 · 发票）与学习辅助（知识框架 · 学习路线 · 知识库）
            </div>
          </div>
        </div>
        <Tabs
          activeKey={activeKey}
          onChange={(key) => setParams(key === 'office' ? {} : { tab: key })}
          items={[
            { key: 'office', label: '⚡ 办公自动化', children: <Office /> },
            { key: 'learning', label: '📚 学习辅助', children: <Learning /> },
          ]}
        />
      </div>
    </AppShell>
  );
}
