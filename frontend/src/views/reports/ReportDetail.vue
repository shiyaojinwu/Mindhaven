<script setup lang="ts">
import { FileText } from "lucide-vue-next";
import type { Report } from "../../types/reports.js";
import type { ReportAnalysis } from "../../types/reports.js";
import { displayDate as date } from "../../utils/date.js";
defineProps<{
  activeReport: Report;
  busy: boolean;
  health: { mode: string };
  reportAnalysis: ReportAnalysis | null;
}>();
const emit = defineEmits<{ analyze: [] }>();
const analyzeReport = () => emit("analyze");
</script>

<template>
  <span class="eyebrow">{{ date(activeReport.createdAt) }} · 状态记录</span>
  <h2>{{ activeReport.surveyTitle }}</h2>
  <p class="muted">发布版本 v{{ activeReport.surveyVersion }}</p>
  <div class="score-display">
    {{ activeReport.score }}<span>/ {{ activeReport.maxScore }}</span>
  </div>
  <p class="reading-text">{{ activeReport.report }}</p>
  <button class="outline-button" :disabled="busy" @click="analyzeReport">
    {{
      busy
        ? "正在整理…"
        : health.mode === "demo"
          ? "查看解读演示"
          : "生成 AI 解读"
    }}<FileText :size="16" />
  </button>
  <div v-if="reportAnalysis" class="analysis-block">
    <span class="tag">{{
      reportAnalysis.mode === "demo"
        ? "演示解读 · 非模型生成"
        : "AI 解读 · 不用于诊断"
    }}</span>
    <p class="reading-text">{{ reportAnalysis.content }}</p>
  </div>
  <div class="answer-review">
    <p v-for="(a, i) in activeReport.answers" :key="i">
      <span>{{ a.title }}</span
      ><strong>{{
        a.type === "TEXT"
          ? a.text || "未填写"
          : a.selectedLabels.join("、") || "未填写"
      }}</strong>
    </p>
  </div>
</template>
