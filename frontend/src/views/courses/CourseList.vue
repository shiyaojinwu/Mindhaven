<script setup lang="ts">
import { Leaf, Clock, BookOpen } from "lucide-vue-next";
import type { Course } from "../../types/courses.js";
defineProps<{ courses: Course[]; completed: string[] }>();
const emit = defineEmits<{ open: [course: Course] }>();
</script>

<template>
  <p class="page-description">每次几分钟，为生活多准备一种应对方式。</p>
  <div class="course-grid">
    <button
      v-for="(c, i) in courses"
      :key="c.id"
      class="course-card"
      @click="emit('open', c)"
    >
      <div :class="['course-art', 'art-' + i]">
        <span class="course-number">0{{ i + 1 }}</span
        ><Leaf :size="64" /><span class="course-category">{{
          c.category
        }}</span>
      </div>
      <div class="course-body">
        <h3>{{ c.title }}</h3>
        <p>{{ c.intro }}</p>
        <span
          ><Clock :size="14" />{{ c.minutes }} 分钟学习<span
            v-if="completed.includes(c.id)"
            class="completed"
            >已完成</span
          ></span
        >
      </div>
    </button>
  </div>
  <div class="soft-banner">
    <BookOpen :size="24" />
    <p>
      已完成
      <strong>{{
        courses.filter((c) => completed.includes(c.id)).length
      }}</strong>
      / {{ courses.length }} 堂小课。按自己的节奏就好。
    </p>
  </div>
</template>
