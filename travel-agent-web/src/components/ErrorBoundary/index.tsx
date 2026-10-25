import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { Button, Result } from 'antd';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * 路由级错误边界：任何页面渲染异常都不再白屏整站，
 * 而是给出可恢复的兜底界面（刷新重置该边界）。
 * key 由外层按 pathname 提供，切换页面自动复位。
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('页面渲染异常', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: 48 }}>
          <Result
            status="error"
            title="页面出错了"
            subTitle={this.state.error.message || '页面渲染时发生异常，刷新后即可恢复'}
            extra={
              <Button type="primary" onClick={() => window.location.reload()}>
                刷新页面
              </Button>
            }
          />
        </div>
      );
    }
    return this.props.children;
  }
}
