import { Tag } from 'antd';
import { BookOutlined } from '@ant-design/icons';
import type { AgentCitation } from '../../types/agent';

interface Props {
  citations?: AgentCitation[] | null;
  /** 紧凑模式（聊天气泡 / 过程时间线内）：隐藏标题、缩小间距 */
  compact?: boolean;
  testId?: string;
}

function citationKey(citation: AgentCitation, index: number): string {
  return `${citation.documentId ?? 'doc'}-${citation.version ?? 'v'}-${citation.chunkId ?? index}-${index}`;
}

/** snippet 是命中片段原文：只截断展示，不做 markdown 解析 */
function shortSnippet(snippet?: string | null, max = 180): string {
  const text = snippet?.trim() ?? '';
  if (!text) return '';
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

/**
 * 知识依据列表（P1）：title + 版本 + 相似度 + snippet + source。
 * 空数组/缺失字段一律不渲染，绝不编造来源。
 */
export default function CitationList({ citations, compact, testId = 'citation-list' }: Props) {
  const list = (citations ?? []).filter((c): c is AgentCitation => !!c);
  if (list.length === 0) return null;

  return (
    <div className={`citation-list ${compact ? 'compact' : ''}`} data-testid={testId}>
      {!compact && (
        <div className="citation-head">
          <BookOutlined aria-hidden="true" /> 知识依据{' '}
          <span className="citation-count">{list.length} 条</span>
        </div>
      )}
      {list.map((citation, index) => {
        const title = citation.title?.trim() || '未命名文档';
        const snippet = shortSnippet(citation.snippet);
        const score =
          typeof citation.score === 'number' && Number.isFinite(citation.score)
            ? citation.score.toFixed(3)
            : null;
        const source = citation.source?.trim() || '';
        return (
          <div className="citation-row" key={citationKey(citation, index)}>
            <div className="citation-title">
              <span className="ct-title">{title}</span>
              {typeof citation.version === 'number' && (
                <Tag className="citation-tag">v{citation.version}</Tag>
              )}
              {score != null && <span className="citation-score">相似度 {score}</span>}
            </div>
            {snippet && <div className="citation-snippet">{snippet}</div>}
            {(source || citation.documentId != null || citation.chunkId != null) && (
              <div className="citation-meta">
                {source && <span>来源：{source}</span>}
                {citation.documentId != null && <span>文档 #{citation.documentId}</span>}
                {citation.chunkId != null && <span>片段 #{citation.chunkId}</span>}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
