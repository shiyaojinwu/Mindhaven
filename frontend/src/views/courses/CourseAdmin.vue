<script setup lang="ts">
import { uploadVideo } from "../../api/videos.js";
import {
  changed,
  useUnsavedChanges,
} from "../../composables/useUnsavedChanges.js";
import { ref, onMounted, onUnmounted } from "vue";
import {
  listCourseDrafts,
  videoConfig,
  saveCourse,
  publishCourse,
  archiveCourse,
} from "../../api/courses.js";
import type { Course } from "../../types/courses.js";
import type { CourseDraft as Draft } from "../../types/courses.js";
const courses = ref<Draft[]>([]),
  editing = ref<Draft | null>(null),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const baseline = ref("");
const dirty = changed(editing, baseline);
const leave = useUnsavedChanges(dirty);
async function closeEditor() {
  if (await leave()) editing.value = null;
}
const maxBytes = ref(512 * 1024 * 1024),
  uploading = ref(false),
  uploadPercent = ref(0);
let uploadRequest: ReturnType<typeof uploadVideo> | null = null;
onUnmounted(() => uploadRequest?.abort());
async function upload(event: Event) {
  const element = event.target as HTMLInputElement,
    file = element.files?.[0];
  if (!file || !editing.value) return;
  error.value = "";
  notice.value = "";
  if (!/\.(mp4|webm)$/i.test(file.name)) {
    error.value = "请选择 MP4 或 WebM 视频";
    element.value = "";
    return;
  }
  if (file.size > maxBytes.value) {
    error.value =
      "视频超过 " + Math.round(maxBytes.value / 1024 / 1024) + " MB 限制";
    element.value = "";
    return;
  }
  uploading.value = true;
  uploadPercent.value = 0;
  try {
    const request = uploadVideo(file, (percent) => {
      uploadPercent.value = percent;
    });
    uploadRequest = request;
    const result = await request.result;
    if (editing.value) editing.value.course.videoId = result.id;
    notice.value = "视频上传完成，可预览。保存并发布后成员才能观看。";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    uploading.value = false;
    uploadRequest = null;
    element.value = "";
  }
}
async function load() {
  courses.value = await listCourseDrafts();
  maxBytes.value = (await videoConfig()).maxBytes;
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
    course: {
      id: "",
      title: "",
      category: "情绪觉察",
      minutes: 5,
      intro: "",
      content: "",
    },
    revision: 0,
    publishedRevision: 0,
    status: "DRAFT",
  };
  baseline.value = JSON.stringify(editing.value);
  error.value = "";
  notice.value = "";
}
function edit(d: Draft) {
  editing.value = JSON.parse(JSON.stringify(d));
  baseline.value = JSON.stringify(editing.value);
  error.value = "";
  notice.value = "";
}
async function save(publish: boolean) {
  if (uploading.value || busy.value) return;
  await run(async () => {
    const d = editing.value!;
    editing.value = await saveCourse(d);
    if (publish) {
      const saved = editing.value!;
      editing.value = await publishCourse(saved.id, saved.revision);
    }
    baseline.value = JSON.stringify(editing.value);
    await load();
    notice.value = publish
      ? "课程已发布，机构成员可在微课堂中学习。"
      : "草稿已保存，成员仍看到上次发布的内容。";
  });
}
async function archive(d: Draft) {
  await run(async () => {
    await archiveCourse(d.id, d.revision);
    await load();
    notice.value = "课程已停用，已有学习完成记录保留。";
  });
}
</script>
<template>
  <div class="tenant-toolbar">
    <p class="muted">
      配置本机构的文字与视频课程，发布后显示在成员微课堂和首页。
    </p>
    <button v-if="!editing" class="primary-button" @click="create">
      ＋ 新增课程
    </button>
  </div>
  <p v-if="error" role="alert" class="alert error">{{ error }}</p>
  <p v-if="notice" role="status" class="alert notice">{{ notice }}</p>
  <form
    v-if="editing"
    class="survey-editor"
    @submit.prevent="
      save(($event.submitter as HTMLButtonElement)?.value === 'publish')
    "
  >
    <div class="tenant-toolbar">
      <h2>{{ editing.id ? "编辑课程" : "新增课程" }}</h2>
      <button
        type="button"
        class="text-button"
        :disabled="busy || uploading"
        @click="closeEditor"
      >
        返回课程列表
      </button>
    </div>
    <fieldset :disabled="busy" class="course-fields">
      <label
        >课程标题<input
          v-model="editing.course.title"
          required
          maxlength="120"
          placeholder="例如：给压力留一个出口"
      /></label>
      <div class="tenant-grid">
        <label
          >课程分类<input
            v-model="editing.course.category"
            required
            maxlength="40"
            placeholder="例如：学业压力" /></label
        ><label
          >预计学习分钟<input
            v-model.number="editing.course.minutes"
            type="number"
            min="1"
            max="240"
            required
        /></label>
      </div>
      <label
        >课程简介<textarea
          v-model="editing.course.intro"
          required
          maxlength="500"
          rows="3"
          placeholder="用一两句话介绍这节课"
        ></textarea></label
      ><label
        >课程正文<textarea
          v-model="editing.course.content"
          :required="!editing.course.videoId"
          maxlength="20000"
          rows="12"
          placeholder="输入阅读内容，支持分段换行"
        ></textarea>
      </label>
      <section class="video-editor">
        <label
          >课程视频<input
            type="file"
            accept=".mp4,.webm,video/mp4,video/webm"
            @change="upload"
            :disabled="uploading || busy"
        /></label>
        <p class="muted small">
          支持 MP4 / WebM，单个视频最多
          {{ Math.round(maxBytes / 1024 / 1024) }} MB。推荐 MP4（H.264 +
          AAC）；不自动转码。视频与文字至少填写一项。
        </p>
        <div v-if="uploading" role="status">
          正在上传 {{ uploadPercent }}%
          <progress :value="uploadPercent" max="100"></progress
          ><button
            type="button"
            class="text-button"
            @click="uploadRequest?.abort()"
          >
            取消上传
          </button>
        </div>
        <template v-if="editing.course.videoId"
          ><video
            :key="editing.course.videoId"
            class="course-video"
            controls
            playsinline
            preload="metadata"
            :src="'/api/videos/' + editing.course.videoId"
            @error="error = '视频无法预览，请确认文件有效且浏览器支持该编码'"
          /><button
            type="button"
            class="text-button"
            :disabled="uploading || busy"
            @click="editing.course.videoId = null"
          >
            移除当前视频
          </button>
          <p class="muted small">
            替换或移除视频需要保存并发布才对成员生效。
          </p></template
        >
      </section>
      <p class="muted small">
        正文按纯文本展示，支持换行。保存草稿不会改变成员正在阅读的已发布内容。
      </p>
      <div class="editor-footer">
        <button class="outline-button" value="draft" :disabled="uploading">
          保存课程草稿</button
        ><button class="primary-button" value="publish" :disabled="uploading">
          保存并发布课程
        </button>
      </div>
    </fieldset>
  </form>
  <div v-else class="tenant-grid">
    <article v-for="d in courses" :key="d.id" class="tenant-card">
      <span class="tag"
        >{{
          d.status === "ARCHIVED"
            ? "已停用"
            : d.status === "PUBLISHED"
              ? "已发布"
              : "草稿"
        }}{{
          d.status === "PUBLISHED" && d.revision !== d.publishedRevision
            ? " · 有未发布修改"
            : ""
        }}</span
      >
      <h3>{{ d.course.title }}</h3>
      <p>{{ d.course.intro }}</p>
      <small class="muted"
        >{{ d.course.category }} · {{ d.course.minutes }} 分钟</small
      >
      <div class="button-row">
        <button
          class="outline-button"
          :disabled="busy || uploading"
          @click="edit(d)"
        >
          编辑课程</button
        ><button
          v-if="d.status === 'PUBLISHED'"
          class="text-button"
          :disabled="busy || uploading"
          @click="archive(d)"
        >
          停用课程
        </button>
      </div>
    </article>
    <p v-if="!courses.length" class="empty-state">
      还没有课程，新增一节微课堂吧。
    </p>
  </div>
</template>
<style scoped>
.course-fields {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.course-fields textarea {
  resize: vertical;
}
</style>
