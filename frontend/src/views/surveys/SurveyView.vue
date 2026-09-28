<script setup lang="ts">
import SurveyPanel from "./SurveyPanel.vue";
import { useWorkspace } from "../../layouts/workspaceContext.js";
const { submitted } = useWorkspace();
</script>
<template><SurveyPanel @submitted="submitted" /></template>
