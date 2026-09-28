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

export interface KnowledgeForm {
  title: string;
  topic: string;
  version: string;
  sourceUrl: string;
  text: string;
}

export interface IndexStatus {
  enabled: boolean;
  running: boolean;
  total: number;
  indexed: number;
  pending: number;
  failed: number;
}
export interface IndexResult {
  indexed: number;
  skipped: number;
  failed: number;
}
