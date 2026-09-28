<script setup lang="ts">
import { computed } from "vue";
import { FileText } from "lucide-vue-next";
import type { Message } from "../../types/chat.js";
import type { Citation } from "../../types/knowledge.js";
const props = defineProps<{ message: Message }>();
const cited = computed(() =>
  props.message.citations
    .map((source, index) => ({ source, number: index + 1 }))
    .filter(
      ({ source }) =>
        props.message.citationCheck?.citedIds.includes(source.id) ??
        props.message.content.includes(`[${source.id}]`),
    ),
);
const emit = defineEmits<{ source: [Citation] }>();
</script>
<template>
  <p
    v-if="message.citationCheck?.status === 'INVALID'"
    class="source-note"
    role="status"
  >
    部分引用无法对应本轮资料，请先核对原文。
  </p>
  <details v-if="cited.length" class="message-sources">
    <summary>参考来源 · {{ cited.length }} 篇</summary>
    <div class="citations">
      <button
        v-for="{ source, number } in cited"
        :key="source.id"
        @click="emit('source', source)"
      >
        <FileText :size="13" />[{{ number }}] {{ source.title }}
      </button>
    </div>
  </details>
</template>
<style scoped>
.message-sources {
  margin-top: 9px;
  font-size: 12px;
  color: var(--muted, #786d7d);
}
.message-sources summary {
  cursor: pointer;
  padding: 4px 0;
}
.source-note {
  margin: 8px 0 0;
  color: #805a35;
  font-size: 12px;
  line-height: 1.7;
}
</style>
