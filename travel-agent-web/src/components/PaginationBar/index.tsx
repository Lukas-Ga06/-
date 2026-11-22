import { Button } from 'antd';

export interface PaginationBarProps {
  /** 总条数：后端 pagination.total；后端未分页时传本页条数 */
  total: number;
  /** 当前偏移（0 起） */
  offset: number;
  /** 单页条数 */
  limit: number;
  /** 后端 pagination.hasMore；缺失时传 false */
  hasMore: boolean;
  /** 后端是否返回了 pagination。false → 降级为「单页、无更多」，两个按钮都禁用 */
  paged: boolean;
  /** 翻页请求在途：只禁用按钮，不动列表（避免翻页时整块骨架屏丢滚动位置） */
  loading?: boolean;
  onPrev: () => void;
  onNext: () => void;
}

/**
 * 列表底部翻页条：「共 N 条 · 上一页 / 下一页」。
 * 与后端分页契约（limit/offset + 可选 pagination）解耦：没有 pagination 时按单页降级。
 */
export default function PaginationBar({
  total,
  offset,
  limit,
  hasMore,
  paged,
  loading = false,
  onPrev,
  onNext,
}: PaginationBarProps) {
  const size = limit > 0 ? limit : 20;
  const page = Math.floor(Math.max(offset, 0) / size) + 1;
  const canPrev = paged && offset > 0 && !loading;
  const canNext = paged && hasMore && !loading;

  return (
    <div
      className="pagination-bar"
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 10,
        padding: '10px 2px 2px',
        fontSize: 12,
        color: 'var(--ink-3)',
      }}
    >
      <span>
        共 {total} 条{paged ? ` · 第 ${page} 页` : ' · 单页'}
      </span>
      <span style={{ display: 'flex', gap: 8 }}>
        <Button size="small" disabled={!canPrev} onClick={onPrev}>
          上一页
        </Button>
        <Button size="small" disabled={!canNext} onClick={onNext}>
          下一页
        </Button>
      </span>
    </div>
  );
}
