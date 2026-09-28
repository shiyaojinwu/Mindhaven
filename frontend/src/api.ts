export interface Citation {
  id: string;
  title: string;
  topic: string;
  version: string;
  sourceUrl: string;
  text: string;
  score: number;
}
export interface CitationCheck {
  status: "VALID" | "MISSING" | "INVALID" | "NOT_REQUIRED";
  citedIds: string[];
  invalidIds: string[];
}
export interface RetrievalMatch {
  chunkId: string;
  vectorRank: number | null;
  lexicalRank: number | null;
  vectorScore: number | null;
  lexicalScore: number | null;
  rrfScore: number | null;
}
export interface Message {
  id: string;
  role: string;
  content: string;
  status: string;
  citations: Citation[];
  citationCheck?: CitationCheck | null;
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
  citationCheck?: CitationCheck | null;
  retrievalMode?: string | null;
  retrievalMatches?: RetrievalMatch[] | null;
  contextIds?: string[] | null;
  retrievalConfig?: {
    candidateLimit: number;
    rrfK: number;
    vectorThreshold: number;
  } | null;
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
