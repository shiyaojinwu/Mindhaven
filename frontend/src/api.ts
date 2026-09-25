export interface Citation {
  id: string;
  title: string;
  topic: string;
  version: string;
  sourceUrl: string;
  text: string;
  score: number;
}
export interface Message {
  id: string;
  role: string;
  content: string;
  status: string;
  citations: Citation[];
}
export interface Session {
  id: string;
  title: string;
  createdAt: string;
}
export interface Course {
  videoId?: string | null;
  id: string;
  title: string;
  category: string;
  minutes: number;
  intro: string;
  content: string;
}
export interface Report {
  id: string;
  score: number;
  label: string;
  report: string;
  createdAt: string;
  maxScore: number;
  surveyTitle: string;
  surveyVersion: number;
  answers: {
    questionId: string;
    title: string;
    type: string;
    selectedLabels: string[];
    text: string;
    score: number;
  }[];
}
export interface Post {
  id: string;
  content: string;
  mood: string;
  hugs: number;
  createdAt: string;
}
export interface Metric {
  id: string;
  sessionId: string;
  mode: string;
  rewrittenQuery: string;
  retrievedCount: number;
  contextEstimate: number;
  promptTokens: number | null;
  completionTokens: number | null;
  firstTokenMs: number;
  totalMs: number;
  summaryVersion: number;
  coveredThroughSeq: number;
  citationIdsValid: boolean;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const res = await fetch("/api" + path, {
    method,
    signal: AbortSignal.timeout(120000),
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith("/auth/"))
      window.dispatchEvent(new Event("mindhaven:session-expired"));
    const error = await res.json().catch(() => ({ message: "服务暂时不可用" }));
    throw new ApiError(res.status, error.message ?? "请求失败");
  }
  return res.status === 204 || method === "DELETE"
    ? (undefined as T)
    : res.json();
}
export async function chatStream(
  id: string,
  body: unknown,
  event: (name: string, data: any) => void,
) {
  const res = await fetch(`/api/sessions/${id}/chat`, {
    method: "POST",
    signal: AbortSignal.timeout(180000),
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    if (res.status === 401)
      window.dispatchEvent(new Event("mindhaven:session-expired"));
    const error = await res.json().catch(() => ({ message: "对话连接失败" }));
    throw new ApiError(res.status, error.message);
  }
  if (!res.body) throw new Error("浏览器不支持流式响应");
  const reader = res.body.getReader(),
    decoder = new TextDecoder();
  let buffer = "",
    doneEvent = false;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, "\n");
      let end;
      while ((end = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        let name = "message";
        const lines: string[] = [];
        for (const line of block.split("\n")) {
          if (line.startsWith("event:")) name = line.slice(6).trim();
          if (line.startsWith("data:")) lines.push(line.slice(5).trimStart());
        }
        if (lines.length) {
          const data = JSON.parse(lines.join("\n"));
          if (name === "error") throw new Error(data.message);
          if (name === "done") doneEvent = true;
          event(name, data);
        }
      }
      if (done) break;
    }
    if (!doneEvent) throw new Error("连接中断，回复未完成，请重试");
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

export interface Identity {
  tenantId: string;
  tenantSlug: string;
  tenantName: string;
  userId: string;
  username: string;
  role: string;
}
export interface SurveyOption {
  id: string;
  label: string;
  score: number;
}
export interface Question {
  id: string;
  title: string;
  type: "SINGLE" | "MULTIPLE" | "TEXT";
  required: boolean;
  options: SurveyOption[];
}
export interface SurveySnapshot {
  id: string;
  surveyId: string;
  title: string;
  description: string;
  version: number;
  questions: Question[];
}
export interface SurveyDraft {
  id: string;
  title: string;
  description: string;
  questions: Question[];
  revision: number;
  publishedVersion: number;
  publishedRevision: number;
  status: string;
  updatedAt: string;
}
