<script setup lang="ts">
import { useRoute } from "vue-router";
import SurveyPanel from "./SurveyPanel.vue";
import { useWorkspace } from "../../layouts/workspaceContext.js";
const route = useRoute();
const { submitted } = useWorkspace();
</script>
<template>
  <SurveyPanel
    :initial-id="
      typeof route.query.item === 'string' ? route.query.item : undefined
    "
    @submitted="submitted"
  />
</template>
