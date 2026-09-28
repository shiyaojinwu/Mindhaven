import { api } from "./http.js";
import type { Course, CourseDraft } from "../types/courses.js";
export const listCourses = () => api<Course[]>("/courses");
export const listProgress = () => api<{ id: string }[]>("/progress");
export const completeCourse = (id: string) =>
  api<void>(`/courses/${id}/complete`, "POST");
export const listCourseDrafts = () => api<CourseDraft[]>("/admin/courses");
export const videoConfig = () =>
  api<{ maxBytes: number }>("/admin/videos/config");
export const saveCourse = (draft: CourseDraft) =>
  api<CourseDraft>(
    "/admin/courses" + (draft.id ? "/" + draft.id : ""),
    draft.id ? "PUT" : "POST",
    { ...draft.course, expectedRevision: draft.revision },
  );
export const publishCourse = (id: string, revision: number) =>
  api<CourseDraft>(`/admin/courses/${id}/publish`, "POST", {
    expectedRevision: revision,
  });
export const archiveCourse = (id: string, revision: number) =>
  api<void>(`/admin/courses/${id}/archive`, "POST", {
    expectedRevision: revision,
  });
