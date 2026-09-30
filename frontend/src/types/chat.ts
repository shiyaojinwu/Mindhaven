import type { Citation, CitationCheck, RetrievalMatch } from "./knowledge.js";

export interface Recommendation {
  kind: "course" | "survey";
  id: string;
  title: string;
  description: string;
}
export interface ExecutionStage {
  draft?: string | null;
  phase: string;
  label: string;
  step: number;
  toolName?: string | null;
  toolCallId?: string | null;
  arguments?: string | null;
}
export interface Message {
  execution?: ExecutionStage[];
  id: string;
  role: string;
  content: string;
  status: string;
  citations: Citation[];
  recommendations?: Recommendation[];
  citationCheck?: CitationCheck | null;
}

export interface Session {
  id: string;
  title: string;
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

export interface ChatRun {
  id: string;
  sessionId: string;
  requestId: string;
  message: string;
  status:
    | "QUEUED"
    | "RUNNING"
    | "COMPLETED"
    | "FAILED"
    | "CANCELLED"
    | "INTERRUPTED";
  error: string | null;
}
export interface ChatCommand {
  message: string;
  topic: string;
  version: string;
  rewrite: boolean;
  requestId: string;
}
export type RunEvent =
  | { name: "snapshot-required" | "stream-start"; data: Record<string, unknown> }
  | { name: "transport-error"; data: { message: string } }
  | {
      name: "agent-status";
      data: ExecutionStage;
    }
  | { name: "recommendations"; data: Recommendation[] }
  | { name: "answer-reset"; data: Record<string, never> }
  | { name: "delta"; data: { text: string } }
  | { name: "sources"; data: Citation[] }
  | { name: "done"; data: { message: Message; metrics: Metric } }
  | { name: "error"; data: { message: string } }
  | { name: "terminal"; data: ChatRun };
