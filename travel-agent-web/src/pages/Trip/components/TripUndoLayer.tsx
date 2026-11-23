import UndoBar from '../../../components/UndoBar';
import type { UndoState } from './types';

interface Props {
  /** 短时撤销窗口；null = 没有可撤销的操作 */
  undo: UndoState | null;
  /** 点「撤销」：把状态写回上一个值 */
  onAction: () => void;
  /** 倒计时结束自动收起 */
  onExpire: () => void;
}

/**
 * 短时撤销层（§7.3 第 3 条）：状态一提交完就出现，倒计时结束自动收起。
 * key=token 让每次操作各自重新计时，不会被上一次续命。
 */
export default function TripUndoLayer({ undo, onAction, onExpire }: Props) {
  if (!undo) return null;

  return (
    <UndoBar
      key={undo.token}
      token={undo.token}
      message={undo.message}
      detail={undo.detail}
      onAction={onAction}
      onExpire={onExpire}
    />
  );
}
