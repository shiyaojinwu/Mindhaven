<script setup lang="ts">
import { computed } from "vue";
import type { Citation } from "../../types/knowledge.js";
const props = defineProps<{ content: string; sources: Citation[] }>();
const emit = defineEmits<{ source: [Citation] }>();
// Render ordinary text and known source buttons only; never insert model-supplied HTML.
const parts = computed(() => {
  const result: { text: string; source?: Citation; number?: number }[] = [];
  const pattern =
    /(?<!\\)\[([A-Za-z0-9][A-Za-z0-9_.:\-]*(?:\s*[,，、]\s*[A-Za-z0-9][A-Za-z0-9_.:\-]*)*)\](?!\()/g;
  let cursor = 0;
  for (const match of props.content.matchAll(pattern)) {
    result.push({ text: props.content.slice(cursor, match.index) });
    for (const id of match[1].split(/\s*[,，、]\s*/)) {
      const index = props.sources.findIndex((s) => s.id === id);
      result.push(
        index < 0
          ? { text: `[${id}]` }
          : {
              text: `[${index + 1}]`,
              number: index + 1,
              source: props.sources[index],
            },
      );
    }
    cursor = match.index! + match[0].length;
  }
  result.push({ text: props.content.slice(cursor) });
  return result;
});
</script>
<template>
  <template v-for="(part, i) in parts" :key="i"
    ><button
      v-if="part.source"
      class="inline-source"
      :title="part.source.title"
      :aria-label="`查看来源 ${part.number}：${part.source.title}`"
      @click="emit('source', part.source)"
    >
      {{ part.text }}</button
    ><template v-else>{{ part.text }}</template></template
  >
</template>
<style scoped>
.inline-source {
  display: inline;
  padding: 0 3px;
  border: 0;
  border-radius: 4px;
  color: var(--primary, #855b91);
  background: rgba(139, 102, 163, 0.09);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
.inline-source:hover {
  text-decoration: underline;
}
.inline-source:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}
</style>
