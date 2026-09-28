import { inject, type InjectionKey, type Ref } from "vue";
import type { useChat } from "../views/chat/useChat.js";
import type { Course } from "../types/courses.js";
import type { Report } from "../types/reports.js";
import type { Post } from "../types/posts.js";
import type { Citation } from "../types/knowledge.js";
interface WorkspaceContext {
  chat: ReturnType<typeof useChat>;
  courses: Ref<Course[]>;
  completed: Ref<string[]>;
  reports: Ref<Report[]>;
  posts: Ref<Post[]>;
  postText: Ref<string>;
  postMood: Ref<string>;
  busy: Ref<boolean>;
  health: Ref<{ mode: string }>;
  activeCourse: Ref<Course | null>;
  activeReport: Ref<Report | null>;
  activeSource: Ref<Citation | null>;
  showKnowledge: Ref<boolean>;
  go: (page: string) => Promise<void>;
  prompt: (text: string) => void;
  submitted: (report: Report) => void;
  publishPost: () => Promise<void>;
  hug: (id: string) => Promise<void>;
  removePost: (id: string) => Promise<void>;
}
export const workspaceKey: InjectionKey<WorkspaceContext> = Symbol("workspace");
export function useWorkspace() {
  const workspace = inject(workspaceKey);
  if (!workspace) throw new Error("页面必须在 WorkspaceLayout 内使用");
  return workspace;
}
