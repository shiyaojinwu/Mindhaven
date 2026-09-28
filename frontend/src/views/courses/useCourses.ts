import { ref, type Ref } from "vue";
import {
  listCourses,
  listProgress,
  completeCourse,
} from "../../api/courses.js";
import type { Course } from "../../types/courses.js";
import type { ActionRunner } from "../../types/common.js";
export function useCourses(run: ActionRunner, notice: Ref<string>) {
  const courses = ref<Course[]>([]);
  const completed = ref<string[]>([]);
  const activeCourse = ref<Course | null>(null);
  async function loadCourses() {
    courses.value = await listCourses();
  }
  async function loadProgress() {
    completed.value = (await listProgress()).map((x) => x.id);
  }
  async function finishCourse() {
    const course = activeCourse.value;
    if (!course) return;
    await run(async () => {
      await completeCourse(course.id);
      if (!completed.value.includes(course.id)) completed.value.push(course.id);
      notice.value = "已记录这次学习";
      if (activeCourse.value?.id === course.id) activeCourse.value = null;
    });
  }
  return {
    courses,
    completed,
    activeCourse,
    loadCourses,
    loadProgress,
    finishCourse,
  };
}
