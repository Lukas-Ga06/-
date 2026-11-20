import type { CSSProperties } from 'react';

interface SkeletonProps {
  /** 是否显示骨架，false 时直接渲染 children */
  loading: boolean;
  children?: React.ReactNode;
  className?: string;
}

interface BaseBoneProps {
  width?: string | number;
  height?: string | number;
  radius?: string | number;
  className?: string;
  style?: CSSProperties;
}

function Bone({ width = '100%', height = 14, radius = 6, className = '', style }: BaseBoneProps) {
  return (
    <div
      className={`sk-bone ${className}`}
      style={{ width, height, borderRadius: radius, ...style }}
    />
  );
}

/** 单行文本骨架 */
function Text({ width = '100%', height = 14 }: BaseBoneProps) {
  return <Bone width={width} height={height} />;
}

/** 多行文本骨架 */
function Paragraph({
  lines = 3,
  lineHeight = 14,
  gap = 8,
  width = '100%',
}: {
  lines?: number;
  lineHeight?: number;
  gap?: number;
  width?: string | number;
}) {
  return (
    <div className="sk-paragraph" style={{ display: 'flex', flexDirection: 'column', gap }}>
      {Array.from({ length: lines }).map((_, i) => (
        <Bone key={i} width={i === lines - 1 ? '60%' : width} height={lineHeight} />
      ))}
    </div>
  );
}

/** 圆形头像骨架 */
function Circle({ size = 40 }: { size?: number }) {
  return <Bone width={size} height={size} radius="50%" />;
}

/** 卡片骨架：图标 + 标题 + 数值 + 标签 */
function Card() {
  return (
    <div className="sk-card">
      <Bone width={38} height={38} radius={10} />
      <Bone width={80} height={22} style={{ marginTop: 10 }} />
      <Bone width={60} height={12} style={{ marginTop: 4 }} />
    </div>
  );
}

/** 列表行骨架：图标 + 两行文本 + 右侧数值 */
function ListItem() {
  return (
    <div className="sk-list-item">
      <Bone width={44} height={44} radius={13} className="sk-shrink" />
      <div className="sk-list-main">
        <Bone width="50%" height={15} />
        <Bone width="70%" height={12} style={{ marginTop: 6 }} />
      </div>
      <Bone width={50} height={16} radius={6} className="sk-shrink" />
    </div>
  );
}

/** 统计行骨架（4 列卡片） */
function StatRow() {
  return (
    <div className="sk-stat-row">
      {Array.from({ length: 4 }).map((_, i) => (
        <Card key={i} />
      ))}
    </div>
  );
}

/** 聊天消息骨架 */
function ChatMessage({ align = 'left' }: { align?: 'left' | 'right' }) {
  return (
    <div className={`sk-chat-row ${align}`}>
      <Bone width={28} height={28} radius="50%" className="sk-shrink" />
      <div className="sk-chat-bone">
        <Bone width={align === 'right' ? '60%' : '80%'} height={14} />
        <Bone width="40%" height={14} style={{ marginTop: 6 }} />
      </div>
    </div>
  );
}

function Skeleton({ loading, children, className = '' }: SkeletonProps) {
  if (!loading) return <>{children}</>;
  return <div className={className}>{children}</div>;
}

Skeleton.Text = Text;
Skeleton.Paragraph = Paragraph;
Skeleton.Circle = Circle;
Skeleton.Card = Card;
Skeleton.ListItem = ListItem;
Skeleton.StatRow = StatRow;
Skeleton.ChatMessage = ChatMessage;

export { Bone };
export default Skeleton;
