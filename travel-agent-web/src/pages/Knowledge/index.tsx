import { useState } from 'react';
import { App, Button, Empty, Input, InputNumber, Modal, Popconfirm, Spin, Tag } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import AppShell from '../../components/AppShell';
import {
  addKnowledge,
  getKnowledgeVersion,
  listKnowledgeVersions,
  rollbackKnowledge,
  searchKnowledge,
} from '../../api/knowledge';
import type {
  KnowledgeAddResult,
  KnowledgeSearchHit,
  KnowledgeVersionDetail,
  KnowledgeVersionSummary,
} from '../../types/knowledge';

function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const d = dayjs(value);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : value;
}

/**
 * 旅行知识库：为 Planner 研究链路补充自定义攻略文档，并按需检索 / 回滚版本。
 * 只做旅行域的 RAG 文档管理，不提供通用产物生成。
 */
export default function Knowledge() {
  const { message } = App.useApp();

  // 添加文档
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [addBusy, setAddBusy] = useState(false);
  const [addResult, setAddResult] = useState<KnowledgeAddResult | null>(null);

  // 检索
  const [query, setQuery] = useState('');
  const [searchBusy, setSearchBusy] = useState(false);
  const [hits, setHits] = useState<KnowledgeSearchHit[] | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);

  // 版本与回滚
  const [documentId, setDocumentId] = useState<number | null>(null);
  const [versionDocId, setVersionDocId] = useState<number | null>(null);
  const [versions, setVersions] = useState<KnowledgeVersionSummary[] | null>(null);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  const [rollbackBusy, setRollbackBusy] = useState<number | null>(null);

  // 版本正文预览
  const [versionModal, setVersionModal] = useState<{
    documentId: number;
    versionNo: number;
  } | null>(null);
  const [versionDetail, setVersionDetail] = useState<KnowledgeVersionDetail | null>(null);
  const [versionDetailLoading, setVersionDetailLoading] = useState(false);

  const loadVersions = (id?: number | null) => {
    const target = id ?? documentId;
    if (!target) {
      message.warning('请先填写文档 ID，或从检索结果打开对应版本');
      return;
    }
    setDocumentId(target);
    setVersionDocId(target);
    setVersionsLoading(true);
    setVersionsError(null);
    listKnowledgeVersions(target)
      .then((rows) => setVersions(Array.isArray(rows) ? rows : []))
      .catch((e: any) => setVersionsError(e?.message || '版本列表加载失败'))
      .finally(() => setVersionsLoading(false));
  };

  const doAdd = async () => {
    if (!content.trim()) {
      message.warning('填写知识正文');
      return;
    }
    setAddBusy(true);
    try {
      const result = await addKnowledge({ title: title.trim(), content: content.trim() });
      setAddResult(result);
      setDocumentId(result.documentId);
      message.success(`已入库：${result.chunks} 个切块 · 版本 v${result.versionNo}`);
      loadVersions(result.documentId);
    } catch (e: any) {
      message.error(e?.message || '知识入库失败');
    } finally {
      setAddBusy(false);
    }
  };

  const doSearch = async () => {
    if (!query.trim()) {
      message.warning('输入检索关键词');
      return;
    }
    setSearchBusy(true);
    setSearchError(null);
    try {
      const rows = await searchKnowledge(query.trim());
      setHits(Array.isArray(rows) ? rows : []);
    } catch (e: any) {
      setSearchError(e?.message || '知识检索失败');
    } finally {
      setSearchBusy(false);
    }
  };

  const openVersion = (id: number, versionNo: number) => {
    setVersionModal({ documentId: id, versionNo });
    setVersionDetail(null);
    setVersionDetailLoading(true);
    getKnowledgeVersion(id, versionNo)
      .then(setVersionDetail)
      .catch((e: any) => message.error(e?.message || '版本正文加载失败'))
      .finally(() => setVersionDetailLoading(false));
  };

  const doRollback = async (versionNo: number) => {
    if (!versionDocId) return;
    setRollbackBusy(versionNo);
    try {
      const result = await rollbackKnowledge(versionDocId, versionNo);
      message.success(`已回滚到 v${result.activeVersionNo}，向量索引已重建`);
      loadVersions(versionDocId);
    } catch (e: any) {
      message.error(e?.message || '版本回滚失败');
    } finally {
      setRollbackBusy(null);
    }
  };

  return (
    <AppShell active="knowledge">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>📚 旅行知识库</h1>
            <div className="dg-date">
              补充目的地攻略与旅行笔记，检索结果会进入 Planner 的研究链路；支持版本回滚
            </div>
          </div>
        </div>

        <div className="knowledge-grid">
          <section className="dash-card">
            <div className="dc-head">
              <b>📥 添加文档</b>
              <span className="dg-date">标题可留空（记为「未命名知识」）</span>
            </div>
            <div className="kb-field">
              <label htmlFor="kb-title">标题</label>
              <Input
                id="kb-title"
                size="large"
                maxLength={200}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="例如：杭州西湖两日游攻略"
              />
            </div>
            <div className="kb-field">
              <label htmlFor="kb-content">正文</label>
              <Input.TextArea
                id="kb-content"
                rows={7}
                maxLength={20000}
                showCount
                value={content}
                onChange={(e) => setContent(e.target.value)}
                placeholder="粘贴攻略正文：交通、景点、餐饮、住宿与避坑提示……"
              />
            </div>
            <Button type="primary" size="large" loading={addBusy} onClick={doAdd}>
              入库并切块
            </Button>
            {addResult && (
              <div className="kb-add-result" data-testid="kb-add-result">
                <Tag color="green">文档 #{addResult.documentId}</Tag>
                <Tag color="blue">{addResult.chunks} 个切块</Tag>
                <Tag>版本 v{addResult.versionNo}</Tag>
              </div>
            )}
          </section>

          <section className="dash-card">
            <div className="dc-head">
              <b>🔍 检索知识</b>
              <span className="dg-date">公共知识 + 你上传的文档</span>
            </div>
            <div className="kb-toolbar">
              <Input
                size="large"
                style={{ flex: 1, minWidth: 180 }}
                value={query}
                maxLength={500}
                onChange={(e) => setQuery(e.target.value)}
                onPressEnter={doSearch}
                placeholder="例如：西湖附近适合亲子的酒店"
              />
              <Button
                type="primary"
                size="large"
                icon={<SearchOutlined />}
                loading={searchBusy}
                onClick={doSearch}
              >
                检索
              </Button>
            </div>

            {searchBusy && <Spin style={{ display: 'block', margin: '24px auto' }} />}
            {!searchBusy && searchError && (
              <div className="run-error-box">
                <div className="run-error">⚠ {searchError}</div>
                <Button size="small" icon={<ReloadOutlined />} onClick={doSearch}>
                  重试
                </Button>
              </div>
            )}
            {!searchBusy && !searchError && hits && hits.length === 0 && (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="没有命中知识，换个关键词试试"
              />
            )}
            {!searchBusy && !searchError && hits === null && (
              <div className="runs-empty-line">输入关键词，查看知识库命中片段</div>
            )}
            {!searchBusy && !searchError && hits && hits.length > 0 && (
              <div className="kb-hits">
                {hits.map((hit) => (
                  <div className="kb-hit" key={`${hit.documentId}-${hit.version}-${hit.chunkId}`}>
                    <div className="kb-hit-head">
                      <b>{hit.title}</b>
                      <span className="kb-score">
                        score {Number.isFinite(hit.score) ? hit.score.toFixed(3) : '—'}
                      </span>
                    </div>
                    <div className="kb-hit-snippet">{hit.snippet}</div>
                    <div className="kb-hit-meta">
                      <Tag>文档 #{hit.documentId}</Tag>
                      <Tag>v{hit.version}</Tag>
                      <Tag>切块 #{hit.chunkId}</Tag>
                      <span className="kb-source">{hit.source}</span>
                      <Button size="small" type="link" onClick={() => loadVersions(hit.documentId)}>
                        查看版本
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>

        <section className="dash-card">
          <div className="dc-head">
            <b>🗂️ 文档版本与回滚</b>
            <span className="dg-date">
              {versionDocId ? `当前文档 #${versionDocId}` : '按文档 ID 查询，或从检索结果打开'}
            </span>
          </div>
          <div className="kb-toolbar">
            <InputNumber
              min={1}
              precision={0}
              size="large"
              style={{ width: 180 }}
              value={documentId}
              onChange={(value) => setDocumentId(typeof value === 'number' ? value : null)}
              placeholder="文档 ID"
              aria-label="文档 ID"
            />
            <Button
              size="large"
              icon={<ReloadOutlined />}
              loading={versionsLoading}
              onClick={() => loadVersions()}
            >
              查询版本
            </Button>
          </div>

          {versionsLoading && <Spin style={{ display: 'block', margin: '24px auto' }} />}
          {!versionsLoading && versionsError && (
            <div className="run-error-box">
              <div className="run-error">⚠ {versionsError}</div>
              <Button size="small" icon={<ReloadOutlined />} onClick={() => loadVersions()}>
                重试
              </Button>
            </div>
          )}
          {!versionsLoading && !versionsError && versions === null && (
            <div className="runs-empty-line">尚未查询版本列表</div>
          )}
          {!versionsLoading && !versionsError && versions && versions.length === 0 && (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该文档暂无版本记录" />
          )}
          {!versionsLoading && !versionsError && versions && versions.length > 0 && (
            <div className="kb-versions">
              {versions.map((version) => (
                <div className="kb-version-row" key={version.versionNo}>
                  <Tag color={version.active ? 'green' : 'default'}>
                    v{version.versionNo}
                    {version.active ? ' · 当前' : ''}
                  </Tag>
                  <div className="kb-version-meta">
                    <span className="kb-version-doc">
                      {version.status} · {formatDateTime(version.createdAt)}
                    </span>
                    <span className="kb-version-doc" title={version.fingerprint}>
                      指纹 {version.fingerprint ? `${version.fingerprint.slice(0, 12)}…` : '—'}
                    </span>
                  </div>
                  <Button
                    size="small"
                    onClick={() => versionDocId && openVersion(versionDocId, version.versionNo)}
                  >
                    查看正文
                  </Button>
                  <Popconfirm
                    title={`回滚到 v${version.versionNo}？`}
                    description="目标版本会置为当前版本，并立即重建向量索引。"
                    okText="回滚"
                    cancelText="取消"
                    disabled={version.active}
                    onConfirm={() => doRollback(version.versionNo)}
                  >
                    <Button
                      size="small"
                      danger
                      disabled={version.active}
                      loading={rollbackBusy === version.versionNo}
                    >
                      {version.active ? '已是当前版本' : '回滚到此版本'}
                    </Button>
                  </Popconfirm>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>

      <Modal
        open={!!versionModal}
        title={
          versionModal
            ? `文档 #${versionModal.documentId} · v${versionModal.versionNo} 正文`
            : '版本正文'
        }
        footer={null}
        width={760}
        onCancel={() => {
          setVersionModal(null);
          setVersionDetail(null);
        }}
      >
        {versionDetailLoading ? (
          <Spin style={{ display: 'block', margin: '24px auto' }} />
        ) : versionDetail ? (
          <pre className="kb-version-content">{versionDetail.content}</pre>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="版本正文不可用" />
        )}
      </Modal>
    </AppShell>
  );
}
