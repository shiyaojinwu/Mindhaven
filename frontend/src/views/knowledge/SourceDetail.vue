<script setup lang="ts">
import { ExternalLink } from "lucide-vue-next";
import type { Citation } from "../../types/knowledge.js";
defineProps<{ activeSource: Citation }>();
const safeUrl = (url: string) => (/^https?:\/\//i.test(url) ? url : undefined);
</script>

<template>
  <span class="eyebrow">知识原文 · {{ activeSource.version }}</span>
  <h2>{{ activeSource.title }}</h2>
  <span class="tag">{{ activeSource.topic }}</span>
  <p class="reading-text">{{ activeSource.text }}</p>
  <a
    v-if="safeUrl(activeSource.sourceUrl)"
    :href="safeUrl(activeSource.sourceUrl)"
    target="_blank"
    rel="noopener noreferrer"
    class="text-button"
    >查看外部来源<ExternalLink :size="15"
  /></a>
  <p v-else class="muted small">来源：项目自编演示材料，不是临床权威资料。</p>
  <code>{{ activeSource.id }}</code>
</template>
