<script setup lang="ts">
import { computed } from "vue";
import type { Citation } from "../../types/knowledge.js";
import { renderMarkdown } from "./markdown.js";
const props = defineProps<{ content: string; sources: Citation[] }>();
const emit = defineEmits<{ source: [Citation] }>();
const html = computed(() => renderMarkdown(props.content, props.sources));
function onClick(event: MouseEvent) {
  const button = (event.target as Element).closest<HTMLButtonElement>("button[data-source-index]");
  if (!button) return;
  const source = props.sources[Number(button.dataset.sourceIndex)];
  if (source) emit("source", source);
}
</script>
<template>
  <div class="markdown-content" @click="onClick" v-html="html"></div>
</template>
<style scoped>
:deep(.inline-source) {
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
:deep(.inline-source):hover {
  text-decoration: underline;
}
:deep(.inline-source):focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}
.markdown-content { white-space: normal; overflow-wrap: anywhere; min-width: 0; }
.markdown-content :deep(> :first-child) { margin-top: 0; }
.markdown-content :deep(> :last-child) { margin-bottom: 0; }
.markdown-content :deep(p) { margin: .65em 0; }
.markdown-content :deep(h1), .markdown-content :deep(h2), .markdown-content :deep(h3), .markdown-content :deep(h4), .markdown-content :deep(h5), .markdown-content :deep(h6) { font-size: 1.1em; line-height: 1.5; margin: 1em 0 .4em; }
.markdown-content :deep(ul), .markdown-content :deep(ol) { padding-left: 1.5em; margin: .6em 0; }
.markdown-content :deep(li) { margin: .3em 0; }
.markdown-content :deep(blockquote) { border-left: 3px solid #c4a4bd; margin: .8em 0; padding: .1em .8em; color: #776b7d; }
.markdown-content :deep(pre) { white-space: pre; overflow-x: auto; max-width: 100%; padding: 12px; border-radius: 8px; background: #ece7ef; line-height: 1.6; }
.markdown-content :deep(code) { font-family: ui-monospace, monospace; font-size: .9em; background: #ece7ef; border-radius: 3px; padding: .1em .25em; }
.markdown-content :deep(pre code) { padding: 0; }
.markdown-content :deep(table) { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; margin: .8em 0; }
.markdown-content :deep(th), .markdown-content :deep(td) { border: 1px solid #d9cddd; padding: 6px 10px; }
.markdown-content :deep(a) { color: var(--primary, #855b91); text-decoration: underline; }
.markdown-content :deep(hr) { border: 0; border-top: 1px solid #d9cddd; margin: 1em 0; }
</style>
