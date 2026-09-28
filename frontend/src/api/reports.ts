import { api } from "./http.js";
import type { Report, ReportAnalysis } from "../types/reports.js";
export const listReports = () => api<Report[]>("/reports");
export const getAnalysis = (id: string) =>
  api<ReportAnalysis>(`/reports/${id}/analysis`);
export const generateAnalysis = (id: string) =>
  api<ReportAnalysis>(`/reports/${id}/analysis`, "POST");
