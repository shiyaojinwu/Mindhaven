<script setup lang="ts">
import {
  changed,
  useUnsavedChanges,
  navigationCheck,
} from "../../composables/useUnsavedChanges.js";
import CourseAdmin from "../courses/CourseAdmin.vue";
import { ref, onMounted, inject } from "vue";
import {
  listSurveyDrafts,
  listMembers,
  addMember as createMember,
  saveSurvey,
  publishSurvey,
  archiveSurvey,
  surveyResponses,
} from "../../api/admin.js";
import type { SurveyDraft } from "../../types/surveys.js";
import type { Question } from "../../types/surveys.js";
import type { Report } from "../../types/reports.js";
const list = ref<SurveyDraft[]>([]),
  editing = ref<SurveyDraft | null>(null),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  tab = ref("surveys");
const baseline = ref("");
const dirty = changed(editing, baseline);
const leave = useUnsavedChanges(dirty);
const canLeave = inject(navigationCheck, async () => true);
async function switchTab(next: string) {
  if (next === tab.value) return;
  if (await canLeave()) {
    editing.value = null;
    tab.value = next;
    error.value = "";
    notice.value = "";
  }
}
async function closeEditor() {
  if (await leave()) editing.value = null;
}
const members = ref<{ id: string; username: string; role: string }[]>([]),
  member = ref({ username: "", password: "" });
const responses = ref<
    { id: string; username: string; assessment: Report }[] | null
  >(null),
  responseTitle = ref("");
const uid = () => crypto.randomUUID();
function question(): Question {
  return {
    id: uid(),
    title: "",
    type: "SINGLE",
    required: true,
    options: [
      { id: uid(), label: "", score: 0 },
      { id: uid(), label: "", score: 1 },
    ],
  };
}
async function load() {
  list.value = await listSurveyDrafts();
  members.value = await listMembers();
}
async function run(fn: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(() => run(load));
function create() {
  editing.value = {
    id: "",
    title: "",
    description: "",
    questions: [question()],
    revision: 0,
    publishedVersion: 0,
    publishedRevision: 0,
    status: "DRAFT",
    updatedAt: "",
  };
  responses.value = null;
  baseline.value = JSON.stringify(editing.value);
  error.value = "";
  notice.value = "";
}
function edit(d: SurveyDraft) {
  editing.value = JSON.parse(JSON.stringify(d));
  responses.value = null;
  baseline.value = JSON.stringify(editing.value);
  error.value = "";
  notice.value = "";
}
function changeType(q: Question) {
  q.options =
    q.type === "TEXT" ? [] : q.options.length ? q.options : question().options;
}
function move(i: number, delta: number) {
  const qs = editing.value!.questions;
  [qs[i], qs[i + delta]] = [qs[i + delta], qs[i]];
}
async function persist() {
  const d = editing.value!;
  editing.value = await saveSurvey(d);
  baseline.value = JSON.stringify(editing.value);
  await load();
}
async function save() {
  await run(async () => {
    await persist();
    notice.value = "草稿已保存。已发布版本保持不变。";
  });
}
async function publish() {
  await run(async () => {
    await persist();
    const d = editing.value!;
    editing.value = await publishSurvey(d.id, d.revision);
    baseline.value = JSON.stringify(editing.value);
    await load();
    notice.value = "问卷已发布，机构成员现在可以填写。";
  });
}
async function archive(d: SurveyDraft) {
  await run(async () => {
    await archiveSurvey(d.id, d.revision);
    await load();
    editing.value = null;
    notice.value = "已停止收集，历史答卷仍保留。";
  });
}
async function viewResponses(d: SurveyDraft) {
  await run(async () => {
    responses.value = await surveyResponses(d.id);
    responseTitle.value = d.title;
    editing.value = null;
  });
}
async function addMember() {
  await run(async () => {
    await createMember(member.value);
    member.value = { username: "", password: "" };
    await load();
    notice.value = "成员已创建，可使用机构代码和该账号登录。";
  });
}
</script>
<template>
  <div class="tenant-toolbar">
    <div class="tenant-tabs">
      <button
        :class="{ active: tab === 'surveys' }"
        @click="switchTab('surveys')"
      >
        问卷管理</button
      ><button
        :class="{ active: tab === 'members' }"
        @click="switchTab('members')"
      >
        成员管理</button
      ><button
        :class="{ active: tab === 'courses' }"
        @click="switchTab('courses')"
      >
        微课堂管理
      </button>
    </div>
    <button
      v-if="tab === 'surveys' && !editing"
      class="primary-button"
      @click="create"
    >
      ＋ 录入问卷
    </button>
  </div>
  <p v-if="error" class="alert error" role="alert">{{ error }}</p>
  <p v-if="notice" class="alert notice" role="status">{{ notice }}</p>
  <template v-if="tab === 'surveys'"
    ><form
      v-if="editing"
      class="survey-editor"
      novalidate
      @submit.prevent="save"
    >
      <div class="tenant-toolbar">
        <h2>
          {{ editing.id ? "编辑问卷" : "录入新问卷" }}
          <small class="save-indicator">{{
            !editing.id ? "尚未保存" : dirty ? "有未保存修改" : "已保存"
          }}</small>
        </h2>
        <button type="button" class="text-button" @click="closeEditor">
          返回列表
        </button>
      </div>
      <p class="muted">
        草稿支持修改。发布会生成独立版本，旧答卷保留当时的题目、选项与分值。
      </p>
      <label
        >问卷名称<input
          v-model="editing.title"
          required
          maxlength="120"
          placeholder="例如：新学期适应情况" /></label
      ><label
        >填写说明<textarea
          v-model="editing.description"
          maxlength="2000"
          rows="3"
          placeholder="说明填写目的、时间范围与数据用途"
        ></textarea>
      </label>
      <fieldset
        v-for="(q, i) in editing.questions"
        :key="q.id"
        class="editor-question"
      >
        <legend>第 {{ i + 1 }} 题</legend>
        <div class="tenant-toolbar">
          <label
            >题型<select v-model="q.type" @change="changeType(q)">
              <option value="SINGLE">单选题</option>
              <option value="MULTIPLE">多选题</option>
              <option value="TEXT">文本题</option>
            </select></label
          ><label class="checkbox-label"
            ><input type="checkbox" v-model="q.required" />必填</label
          >
          <div class="button-row">
            <button
              type="button"
              :disabled="i === 0"
              @click="move(i, -1)"
              :aria-label="`上移第${i + 1}题`"
            >
              ↑</button
            ><button
              type="button"
              :disabled="i === editing.questions.length - 1"
              @click="move(i, 1)"
              :aria-label="`下移第${i + 1}题`"
            >
              ↓</button
            ><button
              type="button"
              :disabled="editing.questions.length === 1"
              @click="editing.questions.splice(i, 1)"
            >
              删除题目
            </button>
          </div>
        </div>
        <label
          >题目内容<input
            v-model="q.title"
            required
            maxlength="500"
            placeholder="输入题目"
        /></label>
        <div v-if="q.type !== 'TEXT'">
          <div class="option-header">
            <span>选项内容</span><span>分值</span>
          </div>
          <div v-for="(o, j) in q.options" :key="o.id" class="option-editor">
            <input
              v-model="o.label"
              required
              maxlength="200"
              :aria-label="`第${i + 1}题选项${j + 1}`"
              :placeholder="`选项 ${j + 1}`"
            /><input
              v-model.number="o.score"
              type="number"
              min="0"
              max="100"
              required
              :aria-label="`第${i + 1}题选项${j + 1}分值`"
            /><button
              type="button"
              :disabled="q.options.length <= 2"
              @click="q.options.splice(j, 1)"
              :aria-label="`删除选项${j + 1}`"
            >
              ×
            </button>
          </div>
          <button
            type="button"
            class="text-button"
            :disabled="q.options.length >= 12"
            @click="q.options.push({ id: uid(), label: '', score: 0 })"
          >
            ＋ 添加选项
          </button>
        </div>
        <p v-else class="muted small">文本题不计分，成员最多可填写 2000 字。</p>
      </fieldset>
      <button
        type="button"
        class="outline-button"
        :disabled="editing.questions.length >= 50"
        @click="editing.questions.push(question())"
      >
        ＋ 添加题目
      </button>
      <div class="editor-footer">
        <span class="muted"
          >{{ editing.questions.length }} 道题 ·
          {{
            editing.publishedVersion
              ? "已发布 v" + editing.publishedVersion
              : "尚未发布"
          }}</span
        ><button class="outline-button" :disabled="busy">保存草稿</button
        ><button
          type="button"
          class="primary-button"
          :disabled="busy"
          @click="publish"
        >
          保存并发布新版本
        </button>
      </div>
    </form>
    <template v-else-if="responses"
      ><button class="text-button" @click="responses = null">← 问卷列表</button>
      <h2>{{ responseTitle }} · 收到 {{ responses.length }} 份答卷</h2>
      <p class="muted">仅本机构管理员可查看本机构的问卷提交记录。</p>
      <p v-if="!responses.length" class="empty-state">还没有人提交答卷。</p>
      <details v-for="r in responses" :key="r.id" class="response-card">
        <summary>
          {{ r.username }} · v{{ r.assessment.surveyVersion }} ·
          {{ r.assessment.score }} / {{ r.assessment.maxScore }} 分 ·
          {{ new Date(r.assessment.createdAt).toLocaleString() }}
        </summary>
        <p v-for="a in r.assessment.answers" :key="a.questionId">
          <strong>{{ a.title }}</strong
          ><br />{{
            a.type === "TEXT" ? a.text : a.selectedLabels.join("、") || "未填写"
          }}
        </p>
      </details></template
    >
    <div v-else class="tenant-grid">
      <article v-for="d in list" :key="d.id" class="tenant-card">
        <span class="tag"
          >{{
            d.status === "ARCHIVED"
              ? "已停用"
              : d.publishedVersion
                ? "已发布 v" + d.publishedVersion
                : "草稿"
          }}{{
            d.status === "PUBLISHED" && d.revision !== d.publishedRevision
              ? " · 有未发布修改"
              : ""
          }}</span
        >
        <h3>{{ d.title || "未命名问卷" }}</h3>
        <p>{{ d.description || "暂无填写说明" }}</p>
        <small class="muted">{{ d.questions.length }} 道题</small>
        <div class="button-row">
          <button class="outline-button" @click="edit(d)">编辑</button
          ><button class="text-button" @click="viewResponses(d)">
            查看答卷</button
          ><button
            v-if="d.status === 'PUBLISHED'"
            class="text-button"
            :disabled="busy"
            @click="archive(d)"
          >
            停止收集
          </button>
        </div>
      </article>
    </div></template
  >
  <CourseAdmin v-else-if="tab === 'courses'" />
  <template v-else
    ><div class="tenant-grid">
      <form class="tenant-card" @submit.prevent="addMember">
        <h2>添加机构成员</h2>
        <p class="muted">
          成员可以填写问卷、聊天和学习，不能管理问卷或查看他人的个人记录。
        </p>
        <label
          >用户名<input
            v-model="member.username"
            required
            pattern="[a-zA-Z0-9_.-]{3,40}"
            maxlength="40"
            autocomplete="off" /></label
        ><label
          >初始密码<input
            v-model="member.password"
            type="password"
            required
            minlength="10"
            maxlength="128"
            autocomplete="new-password" /></label
        ><button class="primary-button" :disabled="busy">创建成员</button>
      </form>
      <div class="tenant-card">
        <h2>机构成员 · {{ members.length }}</h2>
        <div v-for="m in members" :key="m.id" class="setting-row">
          <span>{{ m.username }}</span
          ><span class="tag">{{ m.role === "ADMIN" ? "管理员" : "成员" }}</span>
        </div>
      </div>
    </div></template
  >
</template>
