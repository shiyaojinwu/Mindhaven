<script setup lang="ts">
import CourseList from "./CourseList.vue";
import { useWorkspace } from "../../layouts/workspaceContext.js";
const { courses, completed, activeCourse } = useWorkspace();
</script>
<template>
  <CourseList
    :courses="courses"
    :completed="completed"
    @open="activeCourse = $event"
  />
</template>
