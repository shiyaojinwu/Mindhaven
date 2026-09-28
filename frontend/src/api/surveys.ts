import { api } from "./http.js";
import type { SurveySnapshot, AnswerDraft } from "../types/surveys.js";
import type { Report } from "../types/reports.js";
export const listSurveys = () => api<SurveySnapshot[]>("/surveys");
export const getDraft = (id: string, version: number) =>
  api<AnswerDraft>(`/surveys/${id}/draft?version=${version}`);
export const saveDraft = (id: string, body: unknown) =>
  api<AnswerDraft>(`/surveys/${id}/draft`, "PUT", body);
export const submitSurvey = (id: string, body: unknown) =>
  api<Report>(`/surveys/${id}/submit`, "POST", body);
