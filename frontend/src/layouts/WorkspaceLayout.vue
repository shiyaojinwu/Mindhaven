<script setup lang="ts">
import {
  RouterView,
  useRoute,
  useRouter,
  onBeforeRouteUpdate,
  onBeforeRouteLeave,
} from "vue-router";
import { useAuth } from "../composables/useAuth.js";
import { workspaceKey } from "./workspaceContext.js";

import AppDialog from "../components/AppDialog.vue";
import { displayDate as date } from "../utils/date.js";
import CourseDetail from "../views/courses/CourseDetail.vue";
import { useCourses } from "../views/courses/useCourses.js";
import ReportDetail from "../views/reports/ReportDetail.vue";
import { useReports } from "../views/reports/useReports.js";
import { usePosts } from "../views/posts/usePosts.js";
import KnowledgePanel from "../views/knowledge/KnowledgePanel.vue";
import SourceDetail from "../views/knowledge/SourceDetail.vue";
import { useKnowledge } from "../views/knowledge/useKnowledge.js";
import WorkspaceSettings from "../views/settings/WorkspaceSettings.vue";
import { ref, computed, onMounted, watch, provide } from "vue";
import { useChat } from "../views/chat/useChat.js";
import { provideNavigationGuard } from "../composables/useUnsavedChanges.js";
import type { Identity } from "../types/auth.js";
const auth = useAuth();
const identity = auth.identity.value!;
const route = useRoute(),
  router = useRouter();
import {
  Leaf,
  LayoutDashboard,
  MessageCircle,
  ClipboardList,
  BookOpen,
  BarChart3,
  Heart,
  ChevronRight,
  Check,
  Sun,
  Menu,
  X,
  Settings2,
  AlertCircle,
} from "lucide-vue-next";
import { getHealth } from "../api/system.js";
import { listMetrics } from "../api/chat.js";
import type { Citation } from "../types/knowledge.js";
import type { Report } from "../types/reports.js";
const nav = [
  ...(identity.role === "ADMIN"
    ? [{ id: "admin", label: "机构管理", icon: Settings2 }]
    : []),
  { id: "home", label: "我的心屿", icon: LayoutDashboard },
  { id: "chat", label: "AI 倾听室", icon: MessageCircle },
  { id: "survey", label: "心理自评", icon: ClipboardList },
  { id: "courses", label: "微课堂", icon: BookOpen },
  { id: "reports", label: "成长记录", icon: BarChart3 },
  { id: "posts", label: "心灵树洞", icon: Heart },
];
const { showDiscard, resolveDiscard, canLeave } = provideNavigationGuard();
const page = computed(() => String(route.name ?? "home"));
const mobileMenu = ref(false),
  error = ref(""),
  notice = ref(""),
  loading = ref(true),
  busy = ref(false);
const chat = useChat();
const {
  sessions,
  sessionId,
  metrics,
  draft,
  topic,
  version,
  sending,
  openSession,
} = chat;
const health = ref({
  mode: "demo",
  retrieval: "local-keyword",
  retrievalStrategy: "bm25",
});
const {
  courses,
  completed,
  activeCourse,
  loadCourses,
  loadProgress,
  finishCourse,
} = useCourses(run, notice);
const {
  reports,
  activeReport,
  reportAnalysis,
  loadReports,
  analyzeReport,
  acceptReport,
} = useReports(run);
const { posts, postText, postMood, loadPosts, publishPost, hug, removePost } =
  usePosts(run, notice);
const { knowledge, importing, importForm, loadKnowledge, addKnowledge } =
  useKnowledge(run, notice, health);
const activeSource = ref<Citation | null>(null);
const showSettings = ref(false),
  showKnowledge = ref(false);
function closeDialog() {
  activeSource.value = null;
  activeCourse.value = null;
  activeReport.value = null;
  showSettings.value = false;
  showKnowledge.value = false;
  resolveDiscard(false);
}
const modalOpen = computed(
  () =>
    !!(
      activeSource.value ||
      activeCourse.value ||
      activeReport.value ||
      showSettings.value ||
      showKnowledge.value ||
      showDiscard.value
    ),
);
const titles: Record<string, string> = {
  admin: "为每一份倾听，做好准备",
  home: "给自己，一点温柔的时间",
  chat: "这里，可以慢慢说",
  survey: "看见此刻的自己",
  courses: "照顾自己，也可以慢慢学",
  reports: "每一次觉察，都有意义",
  posts: "把心事，轻轻放在这里",
};
const latestMetric = computed(() =>
  metrics.value.find((m) => m.sessionId === sessionId.value),
);
async function go(id: string) {
  if (!nav.some((n) => n.id === id)) return;
  await router.push({ name: id });
}
onBeforeRouteUpdate((to, from) => (to.name === from.name ? true : canLeave()));
onBeforeRouteLeave(() => (auth.identity.value ? canLeave() : true));
watch(page, () => {
  mobileMenu.value = false;
  error.value = "";
});
async function logout() {
  if (!(await canLeave())) return;
  await run(async () => {
    await auth.logout();
    await router.replace({ name: "login" });
  });
}
watch(page, async (value) => {
  if (value === "courses" || value === "home") {
    try {
      await loadCourses();
    } catch (e) {
      error.value = (e as Error).message;
    }
  }
});
async function run(fn: () => Promise<void>) {
  error.value = "";
  busy.value = true;
  try {
    await fn();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "请求失败";
  } finally {
    busy.value = false;
  }
}
async function refresh() {
  const loads: Array<[string, () => Promise<void>]> = [
    [
      "服务状态",
      async () => {
        health.value = await getHealth();
      },
    ],
    ["会话", chat.loadSessions],
    ["知识库", loadKnowledge],
    ["课程", loadCourses],
    ["学习记录", loadProgress],
    ["报告", loadReports],
    ["树洞", loadPosts],
    [
      "指标",
      async () => {
        metrics.value = await listMetrics();
      },
    ],
  ];
  const results = await Promise.allSettled(loads.map(([, load]) => load()));
  const failed = results.flatMap((r, i) =>
    r.status === "rejected" ? [loads[i][0]] : [],
  );
  if (failed.length)
    error.value = failed.join("、") + "暂时未能加载，其他功能仍可使用。";
}
onMounted(async () => {
  await refresh();
  const remembered = window.sessionStorage.getItem("mindhaven:session");
  const selected =
    sessions.value.find((s) => s.id === remembered) ?? sessions.value[0];
  if (selected) {
    try {
      await openSession(selected.id);
    } catch (e) {
      chat.error.value = (e as Error).message;
    }
  }
  loading.value = false;
});
async function newSession() {
  await run(async () => {
    await chat.newSession();
    go("chat");
  });
}
function prompt(text: string) {
  draft.value = text;
  go("chat");
}
function submitted(report: Report) {
  acceptReport(report);
  go("reports");
}
provide(workspaceKey, {
  chat,
  courses,
  completed,
  reports,
  posts,
  postText,
  postMood,
  busy,
  health,
  activeCourse,
  activeReport,
  activeSource,
  showKnowledge,
  go,
  prompt,
  submitted,
  publishPost,
  hug,
  removePost,
});
</script>

<template>
  <div class="app-shell">
    <aside :inert="modalOpen" class="sidebar" :class="{ open: mobileMenu }">
      <a class="brand" href="#" @click.prevent="go('home')"
        ><span class="brand-mark"><Leaf :size="25" /></span
        ><span>心屿<small>MINDHAVEN</small></span></a
      >
      <div class="nav-label">你的心灵栖息地</div>
      <nav>
        <button
          v-for="item in nav"
          :key="item.id"
          :class="['nav-item', { active: page === item.id }]"
          @click="go(item.id)"
        >
          <component :is="item.icon" :size="20" /><span>{{ item.label }}</span
          ><span v-if="page === item.id" class="nav-dot"></span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="little-plant">
          <Leaf :size="24" />
          <p>不必每一天都很好，<br />每一天都可以重新开始。</p>
        </div>
        <button class="profile" @click="showSettings = true">
          <div class="avatar">屿</div>
          <span
            >{{ identity.username
            }}<small>{{ identity.tenantName }}</small></span
          ><Settings2 :size="16" />
        </button>
        <button
          class="logout-button"
          :disabled="sending || busy"
          @click="logout"
        >
          退出登录
        </button>
      </div>
    </aside>
    <button
      v-if="mobileMenu"
      class="backdrop mobile-backdrop"
      aria-label="关闭导航"
      @click="mobileMenu = false"
    ></button>
    <nav class="bottom-nav" aria-label="主要导航" :inert="modalOpen">
      <button
        v-for="id in ['home', 'survey', 'chat', 'courses', 'reports']"
        :key="id"
        :class="{ active: page === id }"
        @click="go(id)"
      >
        <component :is="nav.find((n) => n.id === id)?.icon" :size="20" />
        <span>{{
          {
            home: "首页",
            survey: "问卷",
            chat: "倾听",
            courses: "微课堂",
            reports: "我的",
          }[id]
        }}</span>
      </button>
    </nav>
    <main :inert="modalOpen">
      <header class="topbar">
        <div class="breadcrumb">
          <button
            class="icon-button mobile-only"
            aria-label="打开导航"
            @click="mobileMenu = true"
          >
            <Menu /></button
          ><span>我的空间</span><ChevronRight :size="14" /><strong>{{
            nav.find((n) => n.id === page)?.label
          }}</strong>
        </div>
        <button class="mode-pill" @click="showSettings = true">
          <span></span
          >{{ health.mode === "demo" ? "本地演示模式" : "真实 AI 模式"
          }}<Settings2 :size="13" />
        </button>
      </header>
      <div v-if="error" class="alert error" role="alert">
        <AlertCircle :size="18" /><span>{{ error }}</span
        ><button
          class="icon-button"
          aria-label="关闭错误提示"
          @click="error = ''"
        >
          <X :size="16" />
        </button>
      </div>
      <div v-if="notice" class="alert notice" role="status">
        <Check :size="18" /><span>{{ notice }}</span
        ><button class="icon-button" aria-label="关闭提示" @click="notice = ''">
          <X :size="16" />
        </button>
      </div>
      <div v-if="loading" class="loading">正在准备你的心屿…</div>
      <div
        v-else
        class="page-content"
        :class="{ 'chat-page': page === 'chat' }"
      >
        <div class="page-heading">
          <div>
            <div class="eyebrow">
              {{
                page === "home"
                  ? "A LITTLE SPACE FOR YOURSELF"
                  : "MINDHAVEN / " + page.toUpperCase()
              }}
            </div>
            <h1>{{ titles[page] }}</h1>
            <p v-if="page === 'home'">
              不着急找到答案。今天，先从关心自己开始。
            </p>
          </div>
          <span v-if="page === 'home'" class="today"
            ><Sun :size="18" />{{ date(new Date().toISOString()) }}</span
          >
        </div>

        <RouterView />
        <footer v-if="page !== 'chat'" class="page-footer">
          <Leaf :size="14" />心屿 · 留一点温柔给自己<span>当前账号体验版</span>
        </footer>
      </div>
    </main>

    <AppDialog v-if="modalOpen" :error="error" @close="closeDialog">
      <template v-if="showDiscard">
        <h2>保留这些修改吗？</h2>
        <p class="reading-text">
          还有未保存的内容。可以继续编辑并保存，或放弃本次修改后离开。
        </p>
        <div class="tenant-toolbar">
          <button class="outline-button" @click="resolveDiscard(false)">
            继续编辑
          </button>
          <button class="primary-button" @click="resolveDiscard(true)">
            放弃修改并离开
          </button>
        </div>
      </template>
      <SourceDetail v-else-if="activeSource" :active-source="activeSource" />
      <CourseDetail
        v-else-if="activeCourse"
        :active-course="activeCourse"
        :busy="busy"
        @complete="finishCourse"
      />
      <ReportDetail
        v-else-if="activeReport"
        :active-report="activeReport"
        :busy="busy"
        :health="health"
        :report-analysis="reportAnalysis"
        @analyze="analyzeReport"
      />
      <WorkspaceSettings
        v-else-if="showSettings"
        :identity="identity"
        :health="health"
        @knowledge="
          showSettings = false;
          showKnowledge = true;
        "
      />
      <KnowledgePanel
        v-else-if="showKnowledge"
        :knowledge="knowledge"
        :identity="identity"
        :health="health"
        :busy="busy"
        :importing="importing"
        :latest-metric="latestMetric"
        v-model:topic="topic"
        v-model:version="version"
        v-model:form="importForm"
        @source="
          activeSource = $event;
          showKnowledge = false;
        "
        @add="addKnowledge"
      />
    </AppDialog>
  </div>
</template>
