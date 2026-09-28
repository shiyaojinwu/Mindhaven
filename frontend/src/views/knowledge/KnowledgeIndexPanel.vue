<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { getIndexStatus, syncIndex } from "../../api/knowledge.js";

const props = defineProps<{ count: number }>();
import type { IndexStatus, IndexResult } from "../../types/knowledge.js";
const status = ref<IndexStatus | null>(null);
const syncing = ref(false);
const loading = ref(false);
const message = ref("");
const error = ref("");
async function refresh() {
  loading.value = true;
  try {
    status.value = await getIndexStatus();
    error.value = "";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "无法读取索引状态";
  } finally {
    loading.value = false;
  }
}
async function sync(force = false) {
  if (syncing.value) return;
  if (
    force &&
    !window.confirm(
      "将重新计算全部知识片段的向量，可能产生模型费用。是否继续？",
    )
  )
    return;
  syncing.value = true;
  message.value = "正在同步，已完成的片段会逐条保存。";
  error.value = "";
  try {
    const result = await syncIndex(force);
    message.value = `本次写入 ${result.indexed} 个，跳过 ${result.skipped} 个未变化片段。`;
    await refresh();
    if (result.failed)
      error.value =
        "同步遇到错误，已完成的部分保留。请检查 Embedding 和 Qdrant 服务后重试。";
  } catch (e) {
    await refresh();
    error.value =
      e instanceof Error ? e.message : "同步未完成，请刷新状态后重试";
  } finally {
    syncing.value = false;
  }
}
onMounted(refresh);
watch(() => props.count, refresh);
</script>

<template>
  <section class="index-panel" aria-label="知识向量索引">
    <p v-if="status">
      共 {{ status.total }} 个片段 · 已同步 {{ status.indexed }} · 待同步
      {{ status.pending }} · 失败 {{ status.failed }}
    </p>
    <p v-if="status?.running && !syncing">本机构正在同步，请稍后刷新状态。</p>
    <div class="index-actions">
      <button
        class="outline-button"
        :disabled="syncing || loading || !status?.enabled || status.running"
        @click="sync()"
      >
        {{ syncing ? "正在同步…" : "同步向量索引" }}
      </button>
      <button class="outline-button" :disabled="loading" @click="refresh">
        刷新状态
      </button>
    </div>
    <p role="status" aria-live="polite">{{ message }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <details>
      <summary>索引维护</summary>
      <p>
        仅同步新增或变化的片段。若向量集合被清空，可重新同步全部片段；状态反映最近一次写入结果，不是向量库实时健康检查。
      </p>
      <button
        class="outline-button"
        :disabled="syncing || !status?.enabled || status.running"
        @click="sync(true)"
      >
        重新同步全部片段
      </button>
    </details>
  </section>
</template>

<style scoped>
.index-panel {
  margin-top: 1rem;
}
.index-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
p {
  line-height: 1.6;
}
[role="alert"] {
  color: #b42347;
}
summary {
  cursor: pointer;
}
</style>
