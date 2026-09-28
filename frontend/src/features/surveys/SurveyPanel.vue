<script setup lang="ts">
import { ref, onMounted, computed, watch, onBeforeUnmount } from "vue";
import { api, type SurveySnapshot, type Report } from "../../api";
import { useUnsavedChanges } from "../../shared/useUnsavedChanges";
const emit = defineEmits<{ submitted: [Report] }>();
type Answer = { questionId: string; optionIds: string[]; text: string };
type Draft = { version: number; answers: Answer[]; updatedAt: string | null };
const surveys = ref<SurveySnapshot[]>([]),
  selected = ref<SurveySnapshot | null>(null);
const error = ref(""),
  busy = ref(false),
  loading = ref(true),
  draftLoading = ref(false);
const saving = ref(false),
  savedAt = ref<string | null>(null),
  baseline = ref("{}");
const answers = ref<Record<string, { optionIds: string[]; text: string }>>({});
const dirty = computed(
  () => !!selected.value && JSON.stringify(answers.value) !== baseline.value,
);
const leave = useUnsavedChanges(dirty);
let timer: ReturnType<typeof setTimeout> | undefined;
let pending: Promise<void> = Promise.resolve();
let generation = 0;
onBeforeUnmount(() => {
  clearTimeout(timer);
  generation++;
});
onMounted(async () => {
  try {
    surveys.value = await api("/surveys");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
});
async function open(s: SurveySnapshot) {
  if (!(await leave())) return;
  const revision = ++generation;
  clearTimeout(timer);
  selected.value = s;
  draftLoading.value = true;
  error.value = "";
  try {
    const draft = await api<Draft>(
      `/surveys/${s.surveyId}/draft?version=${s.version}`,
    );
    if (revision !== generation) return;
    answers.value = Object.fromEntries(
      s.questions.map((q) => {
        const saved = draft.answers.find((a) => a.questionId === q.id);
        return [
          q.id,
          { optionIds: saved?.optionIds ?? [], text: saved?.text ?? "" },
        ];
      }),
    );
    baseline.value = JSON.stringify(answers.value);
    savedAt.value = draft.updatedAt;
  } catch (e) {
    error.value = (e as Error).message;
    selected.value = null;
  } finally {
    if (revision === generation) draftLoading.value = false;
  }
}
async function close() {
  if (await leave()) {
    clearTimeout(timer);
    generation++;
    selected.value = null;
  }
}
function saveDraft() {
  if (!selected.value || !dirty.value || draftLoading.value) return pending;
  clearTimeout(timer);
  const s = selected.value,
    revision = generation,
    snapshot = JSON.stringify(answers.value);
  const payload = Object.entries(JSON.parse(snapshot)).map(
    ([questionId, a]) => ({ questionId, ...(a as object) }),
  );
  saving.value = true;
  pending = pending.then(async () => {
    try {
      const saved = await api<Draft>(`/surveys/${s.surveyId}/draft`, "PUT", {
        version: s.version,
        answers: payload,
      });
      if (revision === generation) {
        baseline.value = snapshot;
        savedAt.value = saved.updatedAt;
        error.value = "";
      }
    } catch (e) {
      if (revision === generation)
        error.value = "进度保存失败：" + (e as Error).message;
    } finally {
      if (revision === generation) saving.value = false;
    }
  });
  return pending;
}
watch(
  answers,
  () => {
    clearTimeout(timer);
    if (!draftLoading.value && !busy.value) timer = setTimeout(saveDraft, 700);
  },
  { deep: true, flush: "sync" },
);
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
  if (!selected.value || busy.value) return;
  busy.value = true;
  error.value = "";
  clearTimeout(timer);
  try {
    await pending;
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
    baseline.value = JSON.stringify(answers.value);
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
  <p v-else-if="draftLoading" role="status">正在恢复填写进度…</p>
  <section v-else class="survey-layout">
    <aside class="survey-intro">
      <button class="text-button" @click="close">← 全部问卷</button>
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
      <p class="save-indicator" role="status">
        {{
          saving
            ? "正在保存进度…"
            : dirty
              ? "有未保存的回答"
              : savedAt
                ? "填写进度已保存"
                : "填写后自动保存进度"
        }}
      </p>
      <button
        type="button"
        class="text-button"
        :disabled="!dirty || saving || busy"
        @click="saveDraft"
      >
        保存进度
      </button>
      <p class="survey-disclosure">
        答卷会提供给本机构管理员查看。<br />分数是自定义选项的加总，不代表临床诊断。
      </p>
    </aside>
    <form class="question-list" @submit.prevent="submit">
      <fieldset
        v-for="(q, i) in selected.questions"
        :key="q.id"
        :disabled="busy"
      >
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
              :checked="answers[q.id].optionIds.includes(o.id)"
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
