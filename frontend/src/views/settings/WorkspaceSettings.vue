<script setup lang="ts">
import { Database } from "lucide-vue-next";
import type { Identity } from "../../types/auth.js";
defineProps<{
  identity: Identity;
  health: { mode: string; retrieval: string; retrievalStrategy: string };
}>();
const emit = defineEmits<{ knowledge: [] }>();
</script>

<template>
  <span class="eyebrow">运行状态</span>
  <h2>{{ identity.tenantName }}</h2>
  <div class="setting-row">
    <span>机构代码</span><strong>{{ identity.tenantSlug }}</strong>
  </div>
  <div class="setting-row">
    <span>当前账号</span
    ><strong
      >{{ identity.username }} ·
      {{ identity.role === "ADMIN" ? "管理员" : "成员" }}</strong
    >
  </div>
  <div class="setting-row">
    <span>回答生成</span
    ><strong>{{
      health.mode === "demo" ? "固定演示回复" : "DeepSeek / Spring AI"
    }}</strong>
  </div>
  <div class="setting-row">
    <span>知识检索</span
    ><strong>{{
      health.retrievalStrategy === "hybrid-rrf"
        ? "BM25 + Qdrant · RRF"
        : health.retrieval === "qdrant"
          ? "Qdrant 向量检索"
          : "本地 BM25 检索"
    }}</strong>
  </div>
  <p class="reading-text small">
    这是按机构与用户隔离的本地版本。聊天、问卷和树洞记录保存在后端数据库。演示模式不调用外部模型；真实
    AI 模式会将选中的上下文发送给配置的模型服务。
  </p>
  <button class="outline-button" @click="emit('knowledge')">
    知识与检索设置<Database :size="17" />
  </button>
</template>
