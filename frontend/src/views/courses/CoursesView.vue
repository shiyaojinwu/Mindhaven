<script setup lang="ts">
import { watch } from "vue";
import { useRoute } from "vue-router";
import CourseList from "./CourseList.vue";
import { useWorkspace } from "../../layouts/workspaceContext.js";
const { courses, completed, activeCourse } = useWorkspace();
const route = useRoute();
watch(
  [() => route.query.item, courses],
  ([id]) => {
    if (typeof id === "string")
      activeCourse.value = courses.value.find((c) => c.id === id) ?? null;
  },
  { immediate: true },
);
</script>
<template>
  <CourseList
    :courses="courses"
    :completed="completed"
    @open="activeCourse = $event"
  />
</template>
