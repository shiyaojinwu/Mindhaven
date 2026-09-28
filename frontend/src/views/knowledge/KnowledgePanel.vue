<script setup lang="ts">
import { computed } from "vue";
import { FileText } from "lucide-vue-next";
import type { Citation } from "../../types/knowledge.js";
import type { Identity } from "../../types/auth.js";
import type { Metric } from "../../types/chat.js";
import type { KnowledgeForm } from "../../types/knowledge.js";
import KnowledgeIndexPanel from "./KnowledgeIndexPanel.vue";
import RetrievalDiagnostics from "../chat/RetrievalDiagnostics.vue";
const props = defineProps<{
  knowledge: Citation[];
  identity: Identity;
  health: { retrieval: string };
  busy: boolean;
  importing: boolean;
  latestMetric?: Metric;
}>();
const topic = defineModel<string>("topic", { required: true });
const version = defineModel<string>("version", { required: true });
const importForm = defineModel<KnowledgeForm>("form", { required: true });
const emit = defineEmits<{ source: [citation: Citation]; add: [] }>();
const addKnowledge = () => emit("add");
const topics = computed(() => [
  "全部",
  ...new Set(props.knowledge.map((k) => k.topic)),
]);
const versions = computed(() => [
  ...new Set(props.knowledge.map((k) => k.version)),
]);
</script>

<template>
  <span class="eyebrow">知识与检索</span>
  <h2>让回答有据可查</h2>
  <div class="filter-row">
    <label
      >知识主题<select v-model="topic">
        <option v-for="t in topics" :key="t">{{ t }}</option>
      </select></label
    ><label
      >知识版本<select v-model="version">
        <option v-for="v in versions" :key="v">{{ v }}</option>
      </select></label
    >
  </div>
  <p class="small muted">
    当前共
    {{ knowledge.length }} 个片段。引用回查显示当次检索保存的原文快照。
  </p>
  <div class="source-list">
    <button v-for="k in knowledge" :key="k.id" @click="emit('source', k)">
      <FileText :size="16" />{{ k.title }}<small>{{ k.version }}</small>
    </button>
  </div>
  <details v-if="identity.role === 'ADMIN'">
    <summary>添加知识片段</summary>
    <form class="import-form" @submit.prevent="addKnowledge">
      <label
        >标题<input v-model="importForm.title" required maxlength="120"
      /></label>
      <div class="filter-row">
        <label
          >主题<input
            v-model="importForm.topic"
            required
            maxlength="40" /></label
        ><label
          >版本<input v-model="importForm.version" required maxlength="40"
        /></label>
      </div>
      <label
        >来源链接（可选）<input
          v-model="importForm.sourceUrl"
          type="url"
          maxlength="500" /></label
      ><label
        >原文片段（最多 700 字）<textarea
          v-model="importForm.text"
          required
          maxlength="700"
        ></textarea></label
      ><button class="primary-button" :disabled="busy || importing">
        保存片段
      </button>
    </form>
  </details>
  <KnowledgeIndexPanel
    v-if="health.retrieval === 'qdrant' && identity.role === 'ADMIN'"
    :count="knowledge.length"
  />
  <RetrievalDiagnostics v-if="latestMetric" :metric="latestMetric" />
</template>
