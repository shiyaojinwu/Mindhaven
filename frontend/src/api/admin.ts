import { api } from "./http.js";
import type { SurveyDraft } from "../types/surveys.js";
import type { Report } from "../types/reports.js";
export const listSurveyDrafts = () => api<SurveyDraft[]>("/admin/surveys");
export const listMembers = () =>
  api<{ id: string; username: string; role: string }[]>("/admin/members");
export const addMember = (body: { username: string; password: string }) =>
  api<void>("/admin/members", "POST", body);
export const saveSurvey = (draft: SurveyDraft) =>
  api<SurveyDraft>(
    "/admin/surveys" + (draft.id ? "/" + draft.id : ""),
    draft.id ? "PUT" : "POST",
    { ...draft, expectedRevision: draft.revision },
  );
export const publishSurvey = (id: string, revision: number) =>
  api<SurveyDraft>(`/admin/surveys/${id}/publish`, "POST", {
    expectedRevision: revision,
  });
export const archiveSurvey = (id: string, revision: number) =>
  api<void>(`/admin/surveys/${id}/archive`, "POST", {
    expectedRevision: revision,
  });
export const surveyResponses = (id: string) =>
  api<{ id: string; username: string; assessment: Report }[]>(
    `/admin/surveys/${id}/responses`,
  );
