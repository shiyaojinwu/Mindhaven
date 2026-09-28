<script setup lang="ts">
import ReportList from "./ReportList.vue";
import { useWorkspace } from "../../layouts/workspaceContext.js";
const { reports, activeReport, go } = useWorkspace();
</script>
<template>
  <ReportList
    :reports="reports"
    @open="activeReport = $event"
    @start="go('survey')"
  />
</template>
