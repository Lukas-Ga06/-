import { useMemo, useState } from 'react';
import { App, Button, Empty, Input, Modal, Popconfirm, Select, Spin, Tag } from 'antd';
import {
  BookOutlined,
  CloudUploadOutlined,
  FileTextOutlined,
  HistoryOutlined,
  ReloadOutlined,
  RollbackOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import AppShell from '../../components/AppShell';
import EmptyState from '../../components/EmptyState';
import RunStatus from '../../components/RunStatus';
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
import './knowledge.css';

function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const d = dayjs(value);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : value;
}

/** 后端版本状态 → 用户能读懂的文案（技术状态只出现在诊断详情里） */
function versionStatusText(version: KnowledgeVersionSummary): string {
  if (version.active) return '当前版本';
  if (version.status === 'SKIPPED') return '内容未变化';
  return '历史版本';
}

interface SavedDoc {
  id: number;
  title: string;
}

/**
 * 攻略库：保存目的地攻略与旅行笔记，检索结果会进入 Planner 的研究链路。
 *
 * 用户主流程只说「攻略 / 保存 / 片段 / 历史版本」；
 * provider、指纹、文档编号等技术信息收在每行的「详情与诊断」里，
 * 不在主流程暴露后端契约字段名。
 */
export default function Knowledge() {
  const { message } = App.useApp();

  // 保存攻略
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [addBusy, setAddBusy] = useState(false);
  const [addResult, setAddResult] = useState<KnowledgeAddResult | null>(null);

  // 检索
  const [query, setQuery] = useState('');
  const [searchBusy, setSearchBusy] = useState(false);
  const [hits, setHits] = useState<KnowledgeSearchHit[] | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);

  // 历史版本
  const [documentId, setDocumentId] = useState<number | null>(null);
  const [versions, setVersions] = useState<KnowledgeVersionSummary[] | null>(null);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versionsError, setVersionsError] = useState<string | null>(null);
  const [rollbackBusy, setRollbackBusy] = useState<number | null>(null);
  const [savedDocs, setSavedDocs] = useState<SavedDoc[]>([]);

  // 版本正文预览
  const [versionModal, setVersionModal] = useState<{
    documentId: number;
    versionNo: number;
  } | null>(null);
  const [versionDetail, setVersionDetail] = useState<KnowledgeVersionDetail | null>(null);
  const [versionDetailLoading, setVersionDetailLoading] = useState(false);

  /** 记住攻略标题：历史版本下拉与命中结果都从「已保存攻略」里选，而不是让用户填文档 ID */
  const rememberDoc = (id: number, docTitle: string) => {
    setSavedDocs((prev) =>
      prev.some((doc) => doc.id === id) ? prev : [{ id, title: docTitle }, ...prev],
    );
  };

  const docOptions = useMemo(
    () =>
      savedDocs.map((doc) => ({
        value: doc.id,
        label: doc.title,
      })),
    [savedDocs],
  );

  const loadVersions = (id?: number | null) => {
    const target = id ?? documentId;
    if (!target) {
      message.warning('先从已保存攻略里选一篇');
      return;
    }
    setDocumentId(target);
    setVersionsLoading(true);
    setVersionsError(null);
    listKnowledgeVersions(target)
      .then((rows) => setVersions(Array.isArray(rows) ? rows : []))
      .catch((e: any) => setVersionsError(e?.message || '历史版本加载失败'))
      .finally(() => setVersionsLoading(false));
  };

  const doAdd = async () => {
    if (!content.trim()) {
      message.warning('写点攻略正文再保存');
      return;
    }
    setAddBusy(true);
    try {
      const result = await addKnowledge({ title: title.trim(), content: content.trim() });
      const savedTitle = title.trim() || '未命名攻略';
      setAddResult(result);
      setDocumentId(result.documentId);
      rememberDoc(result.documentId, savedTitle);
      setTitle('');
      setContent('');
      message.success('攻略已更新');
      loadVersions(result.documentId);
    } catch (e: any) {
      message.error(e?.message || '攻略保存失败');
    } finally {
      setAddBusy(false);
    }
  };

  const doSearch = async () => {
    if (!query.trim()) {
      message.warning('输入关键词再查');
      return;
    }
    setSearchBusy(true);
    setSearchError(null);
    try {
      const rows = await searchKnowledge(query.trim());
      const list = Array.isArray(rows) ? rows : [];
      setHits(list);
      for (const hit of list) {
        if (hit.documentId) rememberDoc(hit.documentId, hit.title);
      }
    } catch (e: any) {
      setSearchError(e?.message || '攻略查询失败');
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
      .catch((e: any) => message.error(e?.message || '版本内容加载失败'))
      .finally(() => setVersionDetailLoading(false));
  };

  const doRollback = async (versionNo: number) => {
    const target = documentId;
    if (!target) return;
    setRollbackBusy(versionNo);
    try {
      const result = await rollbackKnowledge(target, versionNo);
      message.success(`已切换到第 ${result.activeVersionNo} 版，攻略已更新`);
      loadVersions(target);
    } catch (e: any) {
      message.error(e?.message || '切换版本失败');
    } finally {
      setRollbackBusy(null);
    }
  };

  return (
    <AppShell active="knowledge">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>
              <BookOutlined aria-hidden="true" className="page-title-icon" /> 攻略库
            </h1>
            <div className="dg-date">
              保存目的地攻略与旅行笔记，规划时会优先参考；保存后会生成可回滚的历史版本。
            </div>
          </div>
        </div>

        <div className="knowledge-grid">
          <section className="dash-card">
            <div className="dc-head">
              <b>
                <CloudUploadOutlined aria-hidden="true" /> 保存攻略
              </b>
              <span className="dg-date">标题可留空（记为「未命名攻略」）</span>
            </div>
            <div className="kb-field">
              <label htmlFor="kb-title">攻略标题</label>
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
              <label htmlFor="kb-content">攻略正文</label>
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
              保存攻略
            </Button>
            {addResult && (
              <div className="kb-add-result" data-testid="kb-add-result">
                <RunStatus
                  status="complete"
                  text="攻略已保存"
                  detail={`第 ${addResult.versionNo} 版 · 共 ${addResult.chunks} 个片段`}
                  actions={[
                    {
                      label: '查看历史版本',
                      icon: <HistoryOutlined />,
                      onClick: () => loadVersions(addResult.documentId),
                    },
                  ]}
                />
              </div>
            )}
          </section>

          <section className="dash-card">
            <div className="dc-head">
              <b>
                <SearchOutlined aria-hidden="true" /> 查找攻略
              </b>
              <span className="dg-date">公共攻略 + 你保存的内容</span>
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
                查找
              </Button>
            </div>

            {searchBusy && <Spin style={{ display: 'block', margin: '24px auto' }} />}
            {!searchBusy && searchError && (
              <div className="run-error-box">
                <RunStatus status="failed" text="攻略查询失败" detail={searchError} />
                <Button size="small" icon={<ReloadOutlined />} onClick={doSearch}>
                  重试
                </Button>
              </div>
            )}
            {!searchBusy && !searchError && hits && hits.length === 0 && (
              <EmptyState
                testId="knowledge-search-empty"
                title="没有找到相关攻略"
                hint="换个更短的关键词，或在上方保存一篇攻略。"
              />
            )}
            {!searchBusy && !searchError && hits === null && (
              <div className="runs-empty-line">输入关键词，查看匹配到的攻略片段</div>
            )}
            {!searchBusy && !searchError && hits && hits.length > 0 && (
              <div className="kb-hits">
                {hits.map((hit) => (
                  <div className="kb-hit" key={`${hit.documentId}-${hit.version}-${hit.chunkId}`}>
                    <div className="kb-hit-head">
                      <b>{hit.title}</b>
                      <span className="kb-score">
                        相关度 {Number.isFinite(hit.score) ? hit.score.toFixed(3) : '—'}
                      </span>
                    </div>
                    <div className="kb-hit-snippet">{hit.snippet}</div>
                    <div className="kb-hit-meta">
                      <Tag>第 {hit.version} 版</Tag>
                      <Tag>片段 {hit.chunkId}</Tag>
                      <span className="kb-source">来源：{hit.source}</span>
                      <Button
                        size="small"
                        type="link"
                        onClick={() => {
                          rememberDoc(hit.documentId, hit.title);
                          loadVersions(hit.documentId);
                        }}
                      >
                        查看历史版本
                      </Button>
                      <details className="kb-details">
                        <summary>详情与诊断</summary>
                        <span>
                          攻略编号 {hit.documentId} · 版本 {hit.version} · 片段 {hit.chunkId}
                        </span>
                      </details>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>

        <section className="dash-card">
          <div className="dc-head">
            <b>
              <HistoryOutlined aria-hidden="true" /> 历史版本
            </b>
            <span className="dg-date">保存新内容会产生新版本，可随时切回旧版本</span>
          </div>
          <div className="kb-toolbar">
            <Select
              showSearch
              optionFilterProp="label"
              size="large"
              style={{ minWidth: 240, flex: '0 1 360px' }}
              value={documentId ?? undefined}
              onChange={(value: number) => {
                setDocumentId(value);
                setVersions(null);
                setVersionsError(null);
              }}
              options={docOptions}
              placeholder="从已保存攻略中选择"
              aria-label="从已保存攻略中选择"
              notFoundContent="还没有已保存的攻略"
            />
            <Button
              size="large"
              icon={<FileTextOutlined />}
              loading={versionsLoading}
              onClick={() => loadVersions()}
              disabled={!documentId}
            >
              查看历史版本
            </Button>
          </div>
          {docOptions.length === 0 && (
            <div className="runs-empty-line">保存或查找一篇攻略后，就能在这里查看历史版本。</div>
          )}

          {versionsLoading && <Spin style={{ display: 'block', margin: '24px auto' }} />}
          {!versionsLoading && versionsError && (
            <div className="run-error-box">
              <RunStatus status="failed" text="历史版本加载失败" detail={versionsError} />
              <Button size="small" icon={<ReloadOutlined />} onClick={() => loadVersions()}>
                重试
              </Button>
            </div>
          )}
          {!versionsLoading && !versionsError && versions === null && docOptions.length > 0 && (
            <div className="runs-empty-line">选择一篇攻略后查看历史版本</div>
          )}
          {!versionsLoading && !versionsError && versions && versions.length === 0 && (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这篇攻略还没有历史版本" />
          )}
          {!versionsLoading && !versionsError && versions && versions.length > 0 && (
            <div className="kb-versions">
              {versions.map((version) => (
                <div className="kb-version-row" key={version.versionNo}>
                  <Tag color={version.active ? 'green' : 'default'}>
                    第 {version.versionNo} 版{version.active ? ' · 当前' : ''}
                  </Tag>
                  <div className="kb-version-meta">
                    <span className="kb-version-doc">
                      {versionStatusText(version)} · {formatDateTime(version.createdAt)}
                    </span>
                    <details className="kb-details">
                      <summary>详情与诊断</summary>
                      <span>
                        攻略编号 {documentId ?? '—'} · 指纹{' '}
                        {version.fingerprint ? `${version.fingerprint.slice(0, 12)}…` : '—'} ·
                        后端状态 {version.status}
                      </span>
                    </details>
                  </div>
                  <Button
                    size="small"
                    onClick={() => documentId && openVersion(documentId, version.versionNo)}
                  >
                    查看正文
                  </Button>
                  <Popconfirm
                    title={`切回第 ${version.versionNo} 版？`}
                    description="当前攻略内容会被替换，检索依据同步切换。"
                    okText="切回这一版"
                    cancelText="取消"
                    disabled={version.active}
                    onConfirm={() => doRollback(version.versionNo)}
                  >
                    <Button
                      size="small"
                      danger
                      disabled={version.active}
                      loading={rollbackBusy === version.versionNo}
                      icon={<RollbackOutlined />}
                    >
                      {version.active ? '已是当前版本' : '切回这一版'}
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
        title={versionModal ? `第 ${versionModal.versionNo} 版内容` : '历史版本内容'}
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
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="版本内容不可用" />
        )}
      </Modal>
    </AppShell>
  );
}
