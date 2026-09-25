<script setup lang="ts">
import { ref, onMounted, computed } from "vue";
import { api, type SurveySnapshot, type Report } from "./api";
const emit = defineEmits<{ submitted: [Report] }>();
const surveys = ref<SurveySnapshot[]>([]),
  selected = ref<SurveySnapshot | null>(null),
  error = ref(""),
  busy = ref(false),
  loading = ref(true);
const answers = ref<Record<string, { optionIds: string[]; text: string }>>({});
onMounted(async () => {
  try {
    surveys.value = await api("/surveys");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
});
function open(s: SurveySnapshot) {
  selected.value = s;
  answers.value = Object.fromEntries(
    s.questions.map((q) => [q.id, { optionIds: [], text: "" }]),
  );
  error.value = "";
}
function single(q: string, event: Event) {
  answers.value[q].optionIds = [(event.target as HTMLInputElement).value];
}
const chosen = computed(
  () =>
    selected.value?.questions.filter(
      (q) =>
        answers.value[q.id]?.optionIds.length ||
        answers.value[q.id]?.text.trim(),
    ).length ?? 0,
);
async function submit() {
  if (!selected.value) return;
  busy.value = true;
  error.value = "";
  try {
    const r = await api<Report>(
      `/surveys/${selected.value.surveyId}/submit`,
      "POST",
      {
        version: selected.value.version,
        answers: Object.entries(answers.value).map(([questionId, a]) => ({
          questionId,
          ...a,
        })),
      },
    );
    emit("submitted", r);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <p v-if="error" class="alert error" role="alert">{{ error }}</p>
  <p v-if="loading">正在加载问卷…</p>
  <template v-else-if="!selected"
    ><p class="page-description">选择一份问卷，给此刻的自己留一份记录。</p>
    <div v-if="!surveys.length" class="empty-state">
      机构暂时没有已发布的问卷。
    </div>
    <div class="tenant-grid">
      <button
        v-for="s in surveys"
        :key="s.id"
        class="tenant-card"
        @click="open(s)"
      >
        <span class="tag"
          >{{ s.questions.length }} 道题 · v{{ s.version }}</span
        >
        <h3>{{ s.title }}</h3>
        <p>{{ s.description }}</p>
        <strong>开始填写 →</strong>
      </button>
    </div></template
  >
  <section v-else class="survey-layout">
    <aside class="survey-intro">
      <button class="text-button" @click="selected = null">← 全部问卷</button>
      <h2>{{ selected.title }}</h2>
      <p>{{ selected.description }}</p>
      <span class="tag">版本 v{{ selected.version }}</span>
      <div class="survey-progress">
        <span>完成进度</span
        ><strong>{{ chosen }} / {{ selected.questions.length }}</strong>
        <div>
          <i
            :style="{ width: (chosen / selected.questions.length) * 100 + '%' }"
          ></i>
        </div>
      </div>
      <p class="survey-disclosure">
        答卷会提供给本机构管理员查看。<br />分数是自定义选项的加总，不代表临床诊断。
      </p>
    </aside>
    <form class="question-list" @submit.prevent="submit">
      <fieldset v-for="(q, i) in selected.questions" :key="q.id">
        <legend>
          <span>{{ i + 1 }}</span
          >{{ q.title }}
          <small
            >{{ q.required ? "必填" : "选填" }} ·
            {{
              q.type === "MULTIPLE"
                ? "多选"
                : q.type === "TEXT"
                  ? "文本"
                  : "单选"
            }}</small
          >
        </legend>
        <textarea
          v-if="q.type === 'TEXT'"
          v-model="answers[q.id].text"
          :required="q.required"
          maxlength="2000"
          rows="4"
          :aria-label="q.title"
          placeholder="写下你的想法…"
        ></textarea>
        <div v-else class="answer-options">
          <label
            v-for="o in q.options"
            :key="o.id"
            :class="{ checked: answers[q.id].optionIds.includes(o.id) }"
            ><input
              v-if="q.type === 'SINGLE'"
              type="radio"
              :name="q.id"
              :value="o.id"
              :required="q.required"
              @change="single(q.id, $event)"
            /><input
              v-else
              type="checkbox"
              v-model="answers[q.id].optionIds"
              :value="o.id"
            /><span>{{ o.label }}</span></label
          >
        </div>
      </fieldset>
      <button class="primary-button" :disabled="busy">
        {{ busy ? "正在保存…" : "提交并查看记录" }}
      </button>
    </form>
  </section>
</template>
