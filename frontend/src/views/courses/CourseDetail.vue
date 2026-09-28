<script setup lang="ts">
import { ref, watch } from "vue";
import { Check } from "lucide-vue-next";
import type { Course } from "../../types/courses.js";
const props = defineProps<{ activeCourse: Course; busy: boolean }>();
const emit = defineEmits<{ complete: [] }>();
const courseVideoError = ref("");
watch(
  () => props.activeCourse.id,
  () => {
    courseVideoError.value = "";
  },
);
const finishCourse = () => emit("complete");
</script>

<template>
  <span class="eyebrow"
    >{{ activeCourse.category }} · {{ activeCourse.minutes }} 分钟</span
  >
  <h2>{{ activeCourse.title }}</h2>
  <video
    v-if="activeCourse.videoId"
    :key="activeCourse.videoId"
    class="course-video"
    controls
    playsinline
    preload="metadata"
    :src="'/api/videos/' + activeCourse.videoId"
    @error="
      courseVideoError =
        '视频加载失败：课程可能已停用，或当前浏览器不支持该编码。'
    "
  />
  <p v-if="courseVideoError" role="alert" class="alert error">
    {{ courseVideoError }}
  </p>
  <p class="reading-text">{{ activeCourse.content }}</p>
  <p class="muted small">项目自编科普练习，不替代专业咨询。</p>
  <button class="primary-button" :disabled="busy" @click="finishCourse">
    我学完了<Check :size="17" />
  </button>
</template>
