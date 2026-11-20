import { request } from './client';
import type {
  KnowledgeAddResult,
  KnowledgeRollbackResult,
  KnowledgeSearchHit,
  KnowledgeVersionDetail,
  KnowledgeVersionSummary,
} from '../types/knowledge';

/** 知识库搭建：自定义文档入库（同用户 + 同标题复用文档并产生新版本） */
export const addKnowledge = (data: { title: string; content: string }) =>
  request<KnowledgeAddResult>({ url: '/knowledge/add', method: 'post', data });

/** 知识库检索：公共知识 + 本人知识，命中项带 documentId/version/chunkId */
export const searchKnowledge = (query: string) =>
  request<KnowledgeSearchHit[]>({ url: '/knowledge/search', method: 'post', data: { query } });

/** 文档版本列表（按 versionNo 倒序） */
export const listKnowledgeVersions = (documentId: number) =>
  request<KnowledgeVersionSummary[]>({ url: `/knowledge/documents/${documentId}/versions` });

/** 指定版本详情：含正文快照 */
export const getKnowledgeVersion = (documentId: number, versionNo: number) =>
  request<KnowledgeVersionDetail>({
    url: `/knowledge/documents/${documentId}/versions/${versionNo}`,
  });

/** 回滚到指定版本：目标版本置 ACTIVE，后端立即重建向量索引 */
export const rollbackKnowledge = (documentId: number, versionNo: number) =>
  request<KnowledgeRollbackResult>({
    url: `/knowledge/documents/${documentId}/rollback/${versionNo}`,
    method: 'post',
  });
