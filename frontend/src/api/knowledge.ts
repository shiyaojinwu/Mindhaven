import { api } from "./http.js";
import type {
  Citation,
  KnowledgeForm,
  IndexStatus,
  IndexResult,
} from "../types/knowledge.js";
export const listKnowledge = () => api<Citation[]>("/knowledge");
export const addKnowledge = (body: KnowledgeForm) =>
  api<Citation>("/knowledge", "POST", body);
export const getIndexStatus = () => api<IndexStatus>("/knowledge/index/status");
export const syncIndex = (force: boolean) =>
  api<IndexResult>(`/knowledge/index?force=${force}`, "POST");
