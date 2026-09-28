<script setup lang="ts">
import ChatPanel from "./ChatPanel.vue";
import { useWorkspace } from "../../layouts/workspaceContext.js";
const { chat, health, activeSource, showKnowledge } = useWorkspace();
</script>
<template>
  <ChatPanel
    :chat="chat"
    :mode="health.mode"
    @source="activeSource = $event"
    @knowledge="showKnowledge = true"
  />
</template>
