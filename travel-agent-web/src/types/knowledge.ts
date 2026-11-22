/** 旅行知识库：文档入库结果（POST /api/knowledge/add） */
export interface KnowledgeAddResult {
  documentId: number;
  chunks: number;
  versionNo: number;
}

/** 检索命中项（POST /api/knowledge/search）：可追溯到文档 / 版本 / 切块 */
export interface KnowledgeSearchHit {
  documentId: number;
  version: number;
  chunkId: number;
  title: string;
  snippet: string;
  source: string;
  score: number;
}

/** 版本摘要（GET /api/knowledge/documents/{id}/versions） */
export interface KnowledgeVersionSummary {
  versionNo: number;
  /** ACTIVE / INACTIVE / SKIPPED 等后端状态 */
  status: string;
  fingerprint: string;
  createdAt: string;
  active: boolean;
}

/** 指定版本详情（GET /api/knowledge/documents/{id}/versions/{versionNo}） */
export interface KnowledgeVersionDetail extends KnowledgeVersionSummary {
  content: string;
}

/** 回滚结果（POST /api/knowledge/documents/{id}/rollback/{versionNo}） */
export interface KnowledgeRollbackResult {
  activeVersionNo: number;
}
