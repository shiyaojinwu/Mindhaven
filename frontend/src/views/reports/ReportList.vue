<script setup lang="ts">
import { BarChart3, Plus, Leaf, ChevronRight } from "lucide-vue-next";
import type { Report } from "../../types/reports.js";
import { displayDate as date } from "../../utils/date.js";
defineProps<{ reports: Report[] }>();
const emit = defineEmits<{ open: [report: Report]; start: [] }>();
</script>

<template>
  <div class="report-summary">
    <span class="feature-icon lavender"><BarChart3 :size="27" /></span>
    <div>
      <h2>{{ reports.length }} 次，认真地看见自己</h2>
      <p>记录是理解自己的线索，不是给自己贴的标签。</p>
    </div>
    <button class="outline-button" @click="emit('start')">
      再做一次记录<Plus :size="16" />
    </button>
  </div>
  <div v-if="!reports.length" class="empty-state">
    <Leaf :size="38" />
    <h2>你的成长记录，从今天开始</h2>
    <p>完成一次自评，就能在这里回顾自己的感受。</p>
    <button class="primary-button" @click="emit('start')">开始状态自评</button>
  </div>
  <div class="report-list">
    <button
      v-for="r in reports"
      :key="r.id"
      class="report-row"
      @click="emit('open', r)"
    >
      <span class="report-date">{{ date(r.createdAt) }}</span>
      <div>
        <h3>{{ r.surveyTitle }}</h3>
        <p>
          已完成 {{ r.answers.length }} 道题 · v{{ r.surveyVersion }} · 规则解读
        </p>
      </div>
      <strong
        >{{ r.score }}<small> / {{ r.maxScore }}</small></strong
      ><ChevronRight :size="20" />
    </button>
  </div>
</template>
