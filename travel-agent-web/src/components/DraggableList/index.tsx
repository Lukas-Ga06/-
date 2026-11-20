import { useRef, useState, type ReactNode } from 'react';

interface Props<T> {
  items: T[];
  /** 从 item 取唯一 key */
  itemKey: (item: T, index: number) => string | number;
  /** 渲染每行内容 */
  renderItem: (item: T, index: number) => ReactNode;
  /** 拖拽结束后的新顺序回调 */
  onReorder: (items: T[]) => void;
  /** 自定义行 className */
  rowClassName?: string;
}

/**
 * 可拖拽排序的列表。
 * 使用原生 HTML5 拖拽 API，带占位符和拖拽预览。
 * 拖拽过程中显示半透明浮起效果，松开后按新顺序回调 onReorder。
 */
function DraggableList<T>({ items, itemKey, renderItem, onReorder, rowClassName = '' }: Props<T>) {
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);
  const [yOffset, setYOffset] = useState(0);
  const dragRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const handleDragStart = (e: React.DragEvent, index: number) => {
    setDragIndex(index);
    e.dataTransfer.effectAllowed = 'move';
    // 设置一个透明拖拽预览（自定义视觉反馈靠 CSS）
    const ghost = e.currentTarget as HTMLElement;
    ghost.classList.add('dragging');
  };

  const handleDragEnd = (e: React.DragEvent) => {
    (e.currentTarget as HTMLElement).classList.remove('dragging');
    if (dragIndex !== null && overIndex !== null && dragIndex !== overIndex) {
      const next = [...items];
      const [moved] = next.splice(dragIndex, 1);
      next.splice(overIndex, 0, moved);
      onReorder(next);
    }
    setDragIndex(null);
    setOverIndex(null);
    setYOffset(0);
  };

  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dragIndex === null || dragIndex === index) return;
    setOverIndex(index);

    // 计算插入指示线偏移
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const midY = rect.top + rect.height / 2;
    const above = e.clientY < midY;
    setYOffset(above ? -1 : 1);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
  };

  return (
    <div ref={listRef} className="drag-list">
      {items.map((item, index) => {
        const key = itemKey(item, index);
        const isDragging = dragIndex === index;
        const isOver = overIndex === index && dragIndex !== null && dragIndex !== index;
        const showInsertAbove = isOver && yOffset === -1;
        const showInsertBelow = isOver && yOffset === 1;

        return (
          <div key={key} ref={isDragging ? dragRef : undefined}>
            {showInsertAbove && <div className="drag-insert-line" />}
            <div
              className={`drag-row ${rowClassName} ${isDragging ? 'is-dragging' : ''} ${isOver ? 'is-over' : ''}`}
              draggable
              onDragStart={(e) => handleDragStart(e, index)}
              onDragEnd={handleDragEnd}
              onDragOver={(e) => handleDragOver(e, index)}
              onDrop={handleDrop}
            >
              <div className="drag-handle" aria-label="拖拽排序">
                <span />
                <span />
                <span />
              </div>
              <div className="drag-content">{renderItem(item, index)}</div>
            </div>
            {showInsertBelow && <div className="drag-insert-line" />}
          </div>
        );
      })}
    </div>
  );
}

export default DraggableList;
