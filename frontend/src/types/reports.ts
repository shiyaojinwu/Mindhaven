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

export interface ReportAnalysis {
  mode: string;
  content: string;
}
