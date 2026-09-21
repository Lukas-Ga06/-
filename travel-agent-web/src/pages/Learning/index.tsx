import { useEffect, useState } from 'react';
import { App, Button, Empty, Input, Tabs } from 'antd';
import AppShell from '../../components/AppShell';
import {
  addKnowledge, deleteArtifact, generateLearning,
  listArtifacts, searchKnowledge,
  type LearningArtifact,
} from '../../api/assistant';
import type { KnowledgeHit } from '../../types/assistant';

function MdView({ text }: { text: string }) {
  return (
    <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', fontSize: 13, lineHeight: 1.75, margin: 0, color: 'var(--ink)' }}>
      {text}
    </pre>
  );
}

/** 学习辅助模块：知识框架 / 学习路线 / 重点归纳 / 知识库搭建与调用 */
export default function Learning() {
  const { message } = App.useApp();
  const [tab, setTab] = useState('framework');

  // 生成类
  const [topic, setTopic] = useState('');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [artifacts, setArtifacts] = useState<LearningArtifact[]>([]);
  const [lastResult, setLastResult] = useState<string | null>(null);

  // 知识库
  const [kTitle, setKTitle] = useState('');
  const [kContent, setKContent] = useState('');
  const [kQuery, setKQuery] = useState('');
  const [kHits, setKHits] = useState<KnowledgeHit[] | null>(null);
  const [kBusy, setKBusy] = useState(false);

  const kindByTab: Record<string, string> = { framework: 'FRAMEWORK', path: 'PATH', summary: 'SUMMARY' };

  const loadArtifacts = async (k?: string) => {
    try {
      setArtifacts(await listArtifacts(k));
    } catch (e: any) {
      message.error(e.message);
    }
  };

  useEffect(() => {
    loadArtifacts(kindByTab[tab]);
  }, [tab]);

  const doGenerate = async () => {
    const kind = kindByTab[tab];
    if (tab === 'summary' && !text.trim()) { message.warning('粘贴要归纳的内容'); return; }
    if (!topic.trim() && !text.trim()) { message.warning('填写主题或内容'); return; }
    setBusy(true);
    try {
      const r = await generateLearning({ kind, topic, text });
      message.success('已生成');
      setLastResult(r.content);
      setTopic(''); setText('');
      loadArtifacts(kind);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const removeArtifact = async (id: number) => {
    try { await deleteArtifact(id); loadArtifacts(kindByTab[tab]); } catch (e: any) { message.error(e.message); }
  };

  const doAddKnowledge = async () => {
    if (!kContent.trim()) { message.warning('粘贴知识内容'); return; }
    setKBusy(true);
    try {
      const r = await addKnowledge({ title: kTitle || '未命名知识', content: kContent });
      message.success(`已入库：${r.chunks} 个知识块，可直接检索调用`);
      setKTitle(''); setKContent('');
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setKBusy(false);
    }
  };

  const doSearch = async () => {
    if (!kQuery.trim()) { message.warning('输入要检索的问题'); return; }
    setKBusy(true);
    try {
      setKHits(await searchKnowledge(kQuery));
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setKBusy(false);
    }
  };

  return (
    <AppShell active="learning">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>📚 学习辅助</h1>
            <div className="dg-date">知识框架生成 · 学习路线规划 · 重点归纳 · 个人知识库搭建与调用</div>
          </div>
        </div>

        <div className="inset-list fade-up" style={{ padding: '6px 18px 18px' }}>
          <Tabs activeKey={tab} onChange={setTab} items={[
            {
              key: 'framework', label: '知识框架',
              children: (
                <>
                  <Input size="large" placeholder="输入学习主题，如：机器学习 / 明代历史 / 微积分" value={topic} onChange={(e) => setTopic(e.target.value)} />
                  <Button type="primary" style={{ marginTop: 10 }} loading={busy} onClick={doGenerate}>生成知识框架</Button>
                  {lastResult && <div style={{ background: 'var(--bg-grouped)', borderRadius: 12, padding: 14, marginTop: 12 }}><MdView text={lastResult} /></div>}
                  <ArtifactList items={artifacts} onDelete={removeArtifact} />
                </>
              ),
            },
            {
              key: 'path', label: '学习路线',
              children: (
                <>
                  <Input size="large" placeholder="输入学习目标，如：3 个月掌握 Python 数据分析" value={topic} onChange={(e) => setTopic(e.target.value)} />
                  <Button type="primary" style={{ marginTop: 10 }} loading={busy} onClick={doGenerate}>规划学习路线</Button>
                  {lastResult && <div style={{ background: 'var(--bg-grouped)', borderRadius: 12, padding: 14, marginTop: 12 }}><MdView text={lastResult} /></div>}
                  <ArtifactList items={artifacts} onDelete={removeArtifact} />
                </>
              ),
            },
            {
              key: 'summary', label: '重点归纳',
              children: (
                <>
                  <Input size="large" placeholder="主题（可选）" value={topic} onChange={(e) => setTopic(e.target.value)} style={{ marginBottom: 8 }} />
                  <Input.TextArea rows={6} placeholder="粘贴要归纳的文章/课件/笔记内容…" value={text} onChange={(e) => setText(e.target.value)} />
                  <Button type="primary" style={{ marginTop: 10 }} loading={busy} onClick={doGenerate}>归纳重点</Button>
                  {lastResult && <div style={{ background: 'var(--bg-grouped)', borderRadius: 12, padding: 14, marginTop: 12 }}><MdView text={lastResult} /></div>}
                  <ArtifactList items={artifacts} onDelete={removeArtifact} />
                </>
              ),
            },
            {
              key: 'knowledge', label: '知识库',
              children: (
                <>
                  <div style={{ fontSize: 13, color: 'var(--ink-2)', marginBottom: 10 }}>
                    把你的笔记/课件/资料存入个人知识库（自动切块 + 向量化），之后可在任意对话中被检索调用。
                  </div>
                  <Input placeholder="知识标题（可选）" value={kTitle} onChange={(e) => setKTitle(e.target.value)} style={{ marginBottom: 8 }} />
                  <Input.TextArea rows={5} placeholder="粘贴知识内容（空行分段，每段成为一个知识块）…" value={kContent} onChange={(e) => setKContent(e.target.value)} />
                  <Button type="primary" style={{ marginTop: 10 }} loading={kBusy} onClick={doAddKnowledge}>存入知识库</Button>
                  <div style={{ borderTop: '0.5px solid var(--line)', margin: '16px 0 12px' }} />
                  <div style={{ fontSize: 13, color: 'var(--ink-2)', marginBottom: 8 }}>调用检索</div>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <Input placeholder="输入问题，如：考试重点是什么？" value={kQuery} onChange={(e) => setKQuery(e.target.value)} onPressEnter={doSearch} />
                    <Button onClick={doSearch} loading={kBusy}>检索</Button>
                  </div>
                  {kHits && (
                    <div style={{ marginTop: 12 }}>
                      {kHits.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有检索到相关内容" /> :
                        kHits.map((hit, i) => (
                          <div key={i} style={{ background: 'var(--bg-grouped)', borderRadius: 12, padding: 12, marginBottom: 8 }}>
                            <div style={{ fontWeight: 700, fontSize: 13 }}>{hit.title} <span style={{ color: 'var(--ink-3)', fontWeight: 400 }}>相关度 {(hit.score * 100).toFixed(0)}%</span></div>
                            <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 4, lineHeight: 1.7 }}>{hit.snippet}</div>
                          </div>
                        ))}
                    </div>
                  )}
                </>
              ),
            },
          ]} />
        </div>
      </div>
    </AppShell>
  );
}

function ArtifactList({ items, onDelete }: { items: LearningArtifact[]; onDelete: (id: number) => void }) {
  if (items.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有生成记录" style={{ marginTop: 14 }} />;
  }
  return (
    <div style={{ marginTop: 14 }}>
      {items.map((a) => (
        <div key={a.id} style={{ background: 'var(--bg-grouped)', borderRadius: 12, padding: 14, marginBottom: 10 }}>
          <div style={{ fontWeight: 700, marginBottom: 6 }}>
            {a.topic}
            <span style={{ fontSize: 11, color: 'var(--ink-3)', fontWeight: 400, marginLeft: 8 }}>
              {a.createdAt?.slice(0, 16)?.replace('T', ' ')}
            </span>
          </div>
          <MdView text={a.content} />
          <div style={{ textAlign: 'right' }}>
            <Button size="small" type="text" danger onClick={() => onDelete(a.id)}>删除</Button>
          </div>
        </div>
      ))}
    </div>
  );
}
