-- P0-D 安全残余：注销后旧 token 立即失效 / 同名重注册不串号。
--
-- 背景：登录 JWT 原来只有 subject（用户名）。用户注销后，若同名重新注册，
-- 旧 token 的 subject 会映射到新账号；业务接口只按用户名解析用户，旧 token 仍可用。
--
-- 处置：
--   1) users 增加 token_version（单调递增）。JWT 写入 uid 与 ver；
--      认证过滤器按 uid 查用户，并要求库里版本与 token 的 ver 相等，
--      版本一变化，已签发 token 立即全部失效（后续改密/踢下线复用同一机制）。
--   2) 注销时先 token_version + 1 再删用户；同名重注册会得到新的 uid，
--      旧 token 的 uid 查不到用户，直接 401。
--
-- 兼容性：本迁移不要求客户端改协议；但旧 token 没有 uid/ver，部署后会被拒绝一次，
-- 即所有在线会话需要重新登录。这是刻意的「一次整体失效」，见 docs/legal/data-deletion.md §3.1。

ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;