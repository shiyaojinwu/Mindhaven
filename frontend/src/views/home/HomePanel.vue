<script setup lang="ts">
import { ref } from "vue";
import {
  ArrowUpRight,
  ChevronRight,
  ClipboardList,
  BarChart3,
  Heart,
  Leaf,
  Sun,
  MessageCircle,
  Clock,
} from "lucide-vue-next";
import type { Course } from "../../types/courses.js";
defineProps<{ courses: Course[]; completed: string[]; reportCount: number }>();
const emit = defineEmits<{
  navigate: [page: string];
  prompt: [text: string];
  course: [course: Course];
}>();
const mood = ref("");
</script>
<template>
  <div class="home-grid">
    <section class="welcome-card">
      <div class="welcome-copy">
        <span class="tag light">AI 倾听室</span>
        <h2>有些心事，<br />说出来就轻了一点。</h2>
        <p>
          关于学习、关系，或只是说不清的情绪。<br />这里有一段属于你的时间。
        </p>
        <button class="cream-button" @click="emit('navigate', 'chat')">
          聊一聊此刻的心情<ArrowUpRight :size="18" /></button
        ><small>AI 提供科普与陪伴，不替代专业咨询。</small>
      </div>
      <div class="island-art" aria-hidden="true">
        <div class="sun-orb"></div>
        <div class="hill hill-back"></div>
        <div class="hill hill-front"></div>
        <div class="art-stem"><i></i><i></i><i></i></div>
        <span class="art-spark s1">✧</span><span class="art-spark s2">✧</span>
      </div>
    </section>
    <section class="mood-card">
      <div class="section-kicker"><span class="tiny-dot"></span>此刻的你</div>
      <h2>今天，心情是什么天气？</h2>
      <p>所有感受，都值得被看见。</p>
      <div class="mood-options">
        <button
          v-for="m in [
            { face: '☀', label: '晴朗' },
            { face: '◒', label: '平静' },
            { face: '☁', label: '低落' },
            { face: '☂', label: '烦乱' },
          ]"
          :key="m.label"
          :class="{ selected: mood === m.label }"
          @click="mood = m.label"
        >
          <span>{{ m.face }}</span
          >{{ m.label }}
        </button>
      </div>
      <div class="mood-note">
        {{
          mood
            ? `此刻的天气是「${mood}」。愿意多说一点吗？`
            : "不用给心情打分，选一个最接近的就好。"
        }}
      </div>
      <button
        class="text-button"
        @click="
          emit(
            'prompt',
            mood ? `我今天感觉有些${mood}，想聊一聊。` : '我想聊聊今天的心情。',
          )
        "
      >
        把心情说给我听<ChevronRight :size="16" />
      </button>
    </section>
  </div>
  <div class="section-header">
    <h2>用一点时间，靠近自己</h2>
    <span>从一个小小的行动开始</span>
  </div>
  <div class="quick-grid">
    <button class="quick-card" @click="emit('navigate', 'survey')">
      <span class="feature-icon peach"><ClipboardList /></span>
      <h3>做一次状态自评</h3>
      <p>填写机构问卷，整理此刻的感受。</p>
      <span class="card-link"
        >选择一份问卷<ArrowUpRight :size="17"
      /></span></button
    ><button class="quick-card" @click="emit('navigate', 'reports')">
      <span class="feature-icon lavender"><BarChart3 /></span>
      <h3>看看自己的变化</h3>
      <p>留住每一次记录，看见走过的路。</p>
      <span class="card-link"
        >{{ reportCount }} 次状态记录<ArrowUpRight :size="17"
      /></span></button
    ><button class="quick-card" @click="emit('navigate', 'posts')">
      <span class="feature-icon sage"><Heart /></span>
      <h3>留下一点心事</h3>
      <p>不用组织好语言，想到什么就写什么。</p>
      <span class="card-link">打开我的树洞<ArrowUpRight :size="17" /></span>
    </button>
  </div>
  <div class="section-header">
    <h2>给心灵的一堂小课</h2>
    <button class="text-button" @click="emit('navigate', 'courses')">
      全部课程<ChevronRight :size="16" />
    </button>
  </div>
  <div class="course-grid">
    <button
      v-for="(c, i) in courses"
      :key="c.id"
      class="course-card"
      @click="emit('course', c)"
    >
      <div :class="['course-art', 'art-' + i]">
        <span class="course-number">0{{ i + 1 }}</span
        ><Leaf v-if="i === 0" :size="64" /><Sun
          v-else-if="i === 1"
          :size="64"
        /><MessageCircle v-else :size="64" /><span class="course-category">{{
          c.category
        }}</span>
      </div>
      <div class="course-body">
        <h3>{{ c.title }}</h3>
        <p>{{ c.intro }}</p>
        <span
          ><Clock :size="14" />{{ c.minutes }} 分钟学习
          <span v-if="completed.includes(c.id)" class="completed"
            >已完成</span
          ></span
        >
      </div>
    </button>
  </div>
</template>
