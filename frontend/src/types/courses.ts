export interface Course {
  videoId?: string | null;
  id: string;
  title: string;
  category: string;
  minutes: number;
  intro: string;
  content: string;
}

export interface CourseDraft {
  id: string;
  course: Course;
  revision: number;
  publishedRevision: number;
  status: string;
}
