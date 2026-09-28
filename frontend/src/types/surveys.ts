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

export type Answer = { questionId: string; optionIds: string[]; text: string };
export type AnswerDraft = {
  version: number;
  answers: Answer[];
  updatedAt: string | null;
};
