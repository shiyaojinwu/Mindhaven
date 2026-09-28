<script setup lang="ts">
import HomePanel from "./HomePanel.vue";
import { useWorkspace } from "../../layouts/workspaceContext.js";
const { courses, completed, reports, go, prompt, activeCourse } =
  useWorkspace();
</script>
<template>
  <HomePanel
    :courses="courses"
    :completed="completed"
    :report-count="reports.length"
    @navigate="go"
    @prompt="prompt"
    @course="activeCourse = $event"
  />
</template>
