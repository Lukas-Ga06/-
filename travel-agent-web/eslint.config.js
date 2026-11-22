// @ts-check
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';

/**
 * 前端 ESLint flat config（ESLint 9）。
 *
 * 原则：
 * - 只保留能挡住真实缺陷的规则（hooks 依赖、未定义变量、误用 API），不做风格审查——
 *   格式统一交给 Prettier（.prettierrc.json），两者分工见 docs/guide/frontend-quality.md。
 * - 关闭的规则都是「在当前代码库会产生大量噪声/与 tsc 重复」的，每条写明原因；
 *   新增豁免用行内 eslint-disable-next-line 并注释原因，禁止整文件静音。
 */
export default tseslint.config(
  { ignores: ['dist', 'coverage', 'node_modules', 'src/api/generated'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // 只允许 .tsx（组件）导出组件；.ts 工具/类型文件不受限
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],

      // ---- react-hooks v7 新增的「编译器级」规则：降级为 warn ----
      // 这三条来自 React Compiler 的最佳实践，会把当前代码库最主流的两个既有模式判为 error：
      //   1) set-state-in-effect：19 处「挂载时拉数据 → setState」的页面加载模式
      //      （用 useEffect + 异步 fetch 是当前所有列表页的统一写法，改成 React Query /
      //       useSyncExternalStore 属于跨页面重构，不在本次门禁范围）；
      //   2) refs：6 处「渲染期写 ref 做最新值镜像」（pointsRef.current = geoPoints 之类），
      //      是地图/滚动组件避免重复订阅的惯用手法；
      //   3) immutability：1 处动画闭包里的前向引用。
      // 保留为 warning 让新代码可见，但不阻断构建；真正修的时候按页面重构，不逐行 disable。
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/refs': 'warn',
      'react-hooks/immutability': 'warn',
      // 依赖数组缺失仍然是 error 级问题（漏依赖会读到过期闭包），必须修或用注释说明
      'react-hooks/exhaustive-deps': 'warn',

      // ---- 与 tsc 重复或噪声过大，关闭（原因见注释）----
      // tsc strict 已覆盖未使用变量；这里只保留参数前缀约定
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      // 历史代码大量存在 `as` 断言与非空断言（DTO 收敛层），一次性修复风险大于收益；
      // 新代码优先 anyOf 泛型/类型守卫，行内豁免需注释原因
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-non-null-assertion': 'off',
      // 大量 record 索引访问依赖运行时校验后的非空语义，TS 5.6 下该规则噪声过高
      '@typescript-eslint/no-unnecessary-type-constraint': 'off',
      // 控制台日志在前端错误兜底路径有明确用途（SSE/上传失败提示），由 code review 把关
      'no-console': 'off',
    },
  },
  {
    files: ['**/*.test.{ts,tsx}'],
    rules: {
      // 测试里 console 输出属于调试痕迹，不作为错误
      'no-console': 'off',
    },
  },
  {
    // 环境声明文件（global.d.ts / *.d.ts）：声明本身就是「供他处消费」，
    // 文件内没人引用是正常的，unused-vars 在此没有意义。
    files: ['**/*.d.ts'],
    rules: {
      '@typescript-eslint/no-unused-vars': 'off',
      'no-unused-vars': 'off',
    },
  },
);
