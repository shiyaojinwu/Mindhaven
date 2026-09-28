<script setup lang="ts">
import HomePanel from "./features/home/HomePanel.vue";
import {
  ref,
  computed,
  onMounted,
  onBeforeUnmount,
  nextTick,
  watch,
  provide,
} from "vue";
import ChatPanel from "./features/chat/ChatPanel.vue";
import RetrievalDiagnostics from "./features/chat/RetrievalDiagnostics.vue";
import { useChat } from "./features/chat/useChat";
import { provideNavigationGuard } from "./shared/useUnsavedChanges";
import SurveyPanel from "./features/surveys/SurveyPanel.vue";
import AdminPanel from "./features/admin/AdminPanel.vue";
import type { Identity } from "./api";
const props = defineProps<{ identity: Identity }>();
const emit = defineEmits<{ logout: [] }>();
import {
  Leaf,
  LayoutDashboard,
  MessageCircle,
  ClipboardList,
  BookOpen,
  BarChart3,
  Heart,
  Plus,
  ChevronRight,
  Check,
  Clock,
  Sun,
  Menu,
  X,
  Settings2,
  ExternalLink,
  FileText,
  RefreshCw,
  Trash2,
  Database,
  AlertCircle,
} from "lucide-vue-next";
import { api, type Citation, type Course, type Report, type Post } from "./api";
const nav = [
  ...(props.identity.role === "ADMIN"
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
const initialPage = window.location.hash.slice(1);
const page = ref(nav.some((n) => n.id === initialPage) ? initialPage : "home"),
  mobileMenu = ref(false),
  error = ref(""),
  notice = ref(""),
  loading = ref(true),
  busy = ref(false);
const chat = useChat();
const {
  sessions,
  sessionId,
  messages,
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
  }),
  knowledge = ref<Citation[]>([]),
  courses = ref<Course[]>([]),
  completed = ref<string[]>([]),
  reports = ref<Report[]>([]),
  posts = ref<Post[]>([]);
const activeSource = ref<Citation | null>(null),
  activeCourse = ref<Course | null>(null),
  activeReport = ref<Report | null>(null),
  showSettings = ref(false),
  showKnowledge = ref(false),
  postText = ref(""),
  postMood = ref("想说说"),
  importing = ref(false);
const courseVideoError = ref("");
watch(activeCourse, () => {
  courseVideoError.value = "";
});
const reportAnalysis = ref<{ mode: string; content: string } | null>(null);
watch(activeReport, async (r) => {
  reportAnalysis.value = null;
  if (r) {
    try {
      const data = await api<{ mode: string; content: string }>(
        `/reports/${r.id}/analysis`,
      );
      if (activeReport.value?.id === r.id)
        reportAnalysis.value = data.content ? data : null;
    } catch {}
  }
});
async function analyzeReport() {
  if (!activeReport.value) return;
  await run(async () => {
    const id = activeReport.value!.id;
    const result = await api<{ mode: string; content: string }>(
      `/reports/${id}/analysis`,
      "POST",
    );
    if (activeReport.value?.id === id) reportAnalysis.value = result;
  });
}
const importForm = ref({
  title: "",
  topic: "情绪觉察",
  version: "v1",
  sourceUrl: "",
  text: "",
});
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
let previousFocus: HTMLElement | null = null;
watch(modalOpen, async (open) => {
  if (open) {
    previousFocus = document.activeElement as HTMLElement;
    await nextTick();
    document.querySelector<HTMLElement>(".modal-close")?.focus();
  } else {
    previousFocus?.focus();
  }
});
function trapFocus(event: KeyboardEvent) {
  if (event.key !== "Tab") return;
  const nodes = Array.from(
    (event.currentTarget as HTMLElement).querySelectorAll<HTMLElement>(
      "button:not(:disabled),a[href],input,select,textarea,summary",
    ),
  );
  const first = nodes[0],
    last = nodes[nodes.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
const titles: Record<string, string> = {
  admin: "为每一份倾听，做好准备",
  home: "给自己，一点温柔的时间",
  chat: "这里，可以慢慢说",
  survey: "看见此刻的自己",
  courses: "照顾自己，也可以慢慢学",
  reports: "每一次觉察，都有意义",
  posts: "把心事，轻轻放在这里",
};
const topics = computed(() => [
  "全部",
  ...new Set(knowledge.value.map((k) => k.topic)),
]);
const versions = computed(() => [
  ...new Set(knowledge.value.map((k) => k.version)),
]);
const latestMetric = computed(() =>
  metrics.value.find((m) => m.sessionId === sessionId.value),
);
const date = (s: string) =>
  new Date(s).toLocaleDateString("zh-CN", { month: "long", day: "numeric" });
async function go(id: string, fromHash = false) {
  if (!nav.some((n) => n.id === id)) return;
  if (id !== page.value && !(await canLeave())) {
    window.history.replaceState(null, "", "#" + page.value);
    return;
  }
  page.value = id;
  if (!fromHash && window.location.hash !== "#" + id)
    window.history.pushState(null, "", "#" + id);
  mobileMenu.value = false;
  error.value = "";
}
async function logout() {
  if (await canLeave()) emit("logout");
}
const onHashChange = () => go(window.location.hash.slice(1) || "home", true);
onMounted(() => window.addEventListener("hashchange", onHashChange));
onBeforeUnmount(() => window.removeEventListener("hashchange", onHashChange));
watch(page, async (value) => {
  if (value === "courses" || value === "home") {
    try {
      courses.value = await api<Course[]>("/courses");
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
        health.value = await api("/health");
      },
    ],
    ["会话", chat.loadSessions],
    [
      "知识库",
      async () => {
        knowledge.value = await api("/knowledge");
      },
    ],
    [
      "课程",
      async () => {
        courses.value = await api("/courses");
      },
    ],
    [
      "学习记录",
      async () => {
        completed.value = (await api<{ id: string }[]>("/progress")).map(
          (x) => x.id,
        );
      },
    ],
    [
      "报告",
      async () => {
        reports.value = await api("/reports");
      },
    ],
    [
      "树洞",
      async () => {
        posts.value = await api("/posts");
      },
    ],
    [
      "指标",
      async () => {
        metrics.value = await api("/metrics");
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
function submitted(r: Report) {
  reports.value.unshift(r);
  activeReport.value = r;
  go("reports");
}
async function finishCourse() {
  if (!activeCourse.value) return;
  await run(async () => {
    await api(`/courses/${activeCourse.value!.id}/complete`, "POST");
    if (!completed.value.includes(activeCourse.value!.id))
      completed.value.push(activeCourse.value!.id);
    notice.value = "已记录这次学习";
    activeCourse.value = null;
  });
}
async function publishPost() {
  if (!postText.value.trim()) return;
  await run(async () => {
    const p = await api<Post>("/posts", "POST", {
      content: postText.value,
      mood: postMood.value,
    });
    posts.value.unshift(p);
    postText.value = "";
    notice.value = "你的心事已经保存";
  });
}
async function hug(id: string) {
  await run(async () => {
    const p = await api<Post>(`/posts/${id}/hug`, "POST");
    posts.value = posts.value.map((x) => (x.id === id ? p : x));
  });
}
async function removePost(id: string) {
  await run(async () => {
    await api(`/posts/${id}`, "DELETE");
    posts.value = posts.value.filter((x) => x.id !== id);
  });
}
async function addKnowledge() {
  if (importing.value) return;
  importing.value = true;
  await run(async () => {
    await api("/knowledge", "POST", importForm.value);
    await refresh();
    importForm.value = {
      ...importForm.value,
      title: "",
      text: "",
      sourceUrl: "",
    };
    notice.value =
      health.value.retrieval === "qdrant"
        ? "材料已保存，请点击「同步向量索引」后用于检索"
        : "材料已保存，可立即检索";
  });
  importing.value = false;
}
const safeUrl = (url: string) => (/^https?:\/\//i.test(url) ? url : undefined);
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

        <HomePanel
          v-if="page === 'home'"
          :courses="courses"
          :completed="completed"
          :report-count="reports.length"
          @navigate="go"
          @prompt="prompt"
          @course="activeCourse = $event"
        />

        <ChatPanel
          v-else-if="page === 'chat'"
          :chat="chat"
          :mode="health.mode"
          @source="activeSource = $event"
          @knowledge="showKnowledge = true"
        />

        <template v-else-if="page === 'survey'"
          ><SurveyPanel @submitted="submitted"
        /></template>
        <template v-else-if="page === 'admin'"
          ><AdminPanel v-if="identity.role === 'ADMIN'"
        /></template>

        <template v-else-if="page === 'courses'"
          ><p class="page-description">
            每次几分钟，为生活多准备一种应对方式。
          </p>
          <div class="course-grid">
            <button
              v-for="(c, i) in courses"
              :key="c.id"
              class="course-card"
              @click="activeCourse = c"
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
          </div></template
        >

        <template v-else-if="page === 'reports'"
          ><div class="report-summary">
            <span class="feature-icon lavender"><BarChart3 :size="27" /></span>
            <div>
              <h2>{{ reports.length }} 次，认真地看见自己</h2>
              <p>记录是理解自己的线索，不是给自己贴的标签。</p>
            </div>
            <button class="outline-button" @click="go('survey')">
              再做一次记录<Plus :size="16" />
            </button>
          </div>
          <div v-if="!reports.length" class="empty-state">
            <Leaf :size="38" />
            <h2>你的成长记录，从今天开始</h2>
            <p>完成一次自评，就能在这里回顾自己的感受。</p>
            <button class="primary-button" @click="go('survey')">
              开始状态自评
            </button>
          </div>
          <div class="report-list">
            <button
              v-for="r in reports"
              :key="r.id"
              class="report-row"
              @click="activeReport = r"
            >
              <span class="report-date">{{ date(r.createdAt) }}</span>
              <div>
                <h3>{{ r.surveyTitle }}</h3>
                <p>
                  已完成 {{ r.answers.length }} 道题 · v{{ r.surveyVersion }} ·
                  规则解读
                </p>
              </div>
              <strong
                >{{ r.score }}<small> / {{ r.maxScore }}</small></strong
              ><ChevronRight :size="20" />
            </button></div
        ></template>

        <template v-else-if="page === 'posts'"
          ><div class="tree-layout">
            <section class="post-compose">
              <span class="feature-icon sage"><Heart :size="26" /></span>
              <h2>今天想留下些什么？</h2>
              <p>这里是本地私人树洞，内容不会发布到公共社区。</p>
              <label class="sr-only" for="post">心事内容</label
              ><textarea
                id="post"
                v-model="postText"
                maxlength="1000"
                placeholder="写下那些还没来得及说的话…"
              ></textarea>
              <div class="post-bottom">
                <label
                  >心情<select v-model="postMood">
                    <option>想说说</option>
                    <option>小确幸</option>
                    <option>有点累</option>
                    <option>期待中</option>
                  </select></label
                ><span>{{ postText.length }} / 1000</span>
              </div>
              <button
                class="primary-button"
                :disabled="!postText.trim() || busy"
                @click="publishPost"
              >
                放进树洞<Leaf :size="17" />
              </button>
            </section>
            <section class="posts-feed">
              <div class="section-header">
                <h2>我的心事小纸条</h2>
                <span>{{ posts.length }} 条记录</span>
              </div>
              <div v-if="!posts.length" class="empty-state">
                <Heart :size="30" />
                <p>还没有纸条。想到什么，就写一点吧。</p>
              </div>
              <article v-for="p in posts" :key="p.id" class="post-card">
                <div class="post-meta">
                  <span class="tag">{{ p.mood }}</span
                  ><time>{{ date(p.createdAt) }}</time>
                </div>
                <p>{{ p.content }}</p>
                <footer>
                  <button
                    class="text-button"
                    :disabled="busy || p.hugs > 0"
                    @click="hug(p.id)"
                  >
                    <Heart :size="17" />{{
                      p.hugs ? "已给自己一个拥抱" : "给自己一个拥抱"
                    }}</button
                  ><button
                    class="icon-button"
                    aria-label="删除这条心事"
                    :disabled="busy"
                    @click="removePost(p.id)"
                  >
                    <Trash2 :size="16" />
                  </button>
                </footer>
              </article>
            </section></div
        ></template>
        <footer v-if="page !== 'chat'" class="page-footer">
          <Leaf :size="14" />心屿 · 留一点温柔给自己<span>当前账号体验版</span>
        </footer>
      </div>
    </main>

    <div
      v-if="
        activeSource ||
        activeCourse ||
        activeReport ||
        showSettings ||
        showKnowledge ||
        showDiscard
      "
      class="modal-overlay"
      @click.self="
        activeSource = null;
        activeCourse = null;
        activeReport = null;
        showSettings = false;
        showKnowledge = false;
        resolveDiscard(false);
      "
    >
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="详细内容"
        tabindex="-1"
        @keydown="trapFocus"
        @keydown.esc="
          activeSource = null;
          activeCourse = null;
          activeReport = null;
          showSettings = false;
          showKnowledge = false;
          resolveDiscard(false);
        "
      >
        <button
          class="modal-close icon-button"
          aria-label="关闭详情"
          @click="
            activeSource = null;
            activeCourse = null;
            activeReport = null;
            showSettings = false;
            showKnowledge = false;
            resolveDiscard(false);
          "
        >
          <X />
        </button>
        <p v-if="error" class="modal-error" role="alert">{{ error }}</p>
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
        <template v-else-if="activeSource"
          ><span class="eyebrow">知识原文 · {{ activeSource.version }}</span>
          <h2>{{ activeSource.title }}</h2>
          <span class="tag">{{ activeSource.topic }}</span>
          <p class="reading-text">{{ activeSource.text }}</p>
          <a
            v-if="safeUrl(activeSource.sourceUrl)"
            :href="safeUrl(activeSource.sourceUrl)"
            target="_blank"
            rel="noopener noreferrer"
            class="text-button"
            >查看外部来源<ExternalLink :size="15"
          /></a>
          <p v-else class="muted small">
            来源：项目自编演示材料，不是临床权威资料。
          </p>
          <code>{{ activeSource.id }}</code></template
        >
        <template v-else-if="activeCourse"
          ><span class="eyebrow"
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
            " />
          <p v-if="courseVideoError" role="alert" class="alert error">
            {{ courseVideoError }}
          </p>
          <p class="reading-text">{{ activeCourse.content }}</p>
          <p class="muted small">项目自编科普练习，不替代专业咨询。</p>
          <button class="primary-button" :disabled="busy" @click="finishCourse">
            我学完了<Check :size="17" /></button
        ></template>
        <template v-else-if="activeReport"
          ><span class="eyebrow"
            >{{ date(activeReport.createdAt) }} · 状态记录</span
          >
          <h2>{{ activeReport.surveyTitle }}</h2>
          <p class="muted">发布版本 v{{ activeReport.surveyVersion }}</p>
          <div class="score-display">
            {{ activeReport.score }}<span>/ {{ activeReport.maxScore }}</span>
          </div>
          <p class="reading-text">{{ activeReport.report }}</p>
          <button
            class="outline-button"
            :disabled="busy"
            @click="analyzeReport"
          >
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
          </div></template
        >
        <template v-else-if="showSettings"
          ><span class="eyebrow">运行状态</span>
          <h2>{{ identity.tenantName }}</h2>
          <div class="setting-row">
            <span>机构代码</span><strong>{{ identity.tenantSlug }}</strong>
          </div>
          <div class="setting-row">
            <span>当前账号</span
            ><strong
              >{{ identity.username }} ·
              {{ identity.role === "ADMIN" ? "管理员" : "成员" }}</strong
            >
          </div>
          <div class="setting-row">
            <span>回答生成</span
            ><strong>{{
              health.mode === "demo" ? "固定演示回复" : "DeepSeek / Spring AI"
            }}</strong>
          </div>
          <div class="setting-row">
            <span>知识检索</span
            ><strong>{{
              health.retrievalStrategy === "hybrid-rrf"
                ? "BM25 + Qdrant · RRF"
                : health.retrieval === "qdrant"
                  ? "Qdrant 向量检索"
                  : "本地 BM25 检索"
            }}</strong>
          </div>
          <p class="reading-text small">
            这是按机构与用户隔离的本地版本。聊天、问卷和树洞记录保存在后端数据库。演示模式不调用外部模型；真实
            AI 模式会将选中的上下文发送给配置的模型服务。
          </p>
          <button
            class="outline-button"
            @click="
              showSettings = false;
              showKnowledge = true;
            "
          >
            知识与检索设置<Database :size="17" /></button
        ></template>
        <template v-else-if="showKnowledge"
          ><span class="eyebrow">知识与检索</span>
          <h2>让回答有据可查</h2>
          <div class="filter-row">
            <label
              >知识主题<select v-model="topic">
                <option v-for="t in topics" :key="t">{{ t }}</option>
              </select></label
            ><label
              >知识版本<select v-model="version">
                <option v-for="v in versions" :key="v">{{ v }}</option>
              </select></label
            >
          </div>
          <p class="small muted">
            当前共
            {{ knowledge.length }} 个片段。引用回查显示当次检索保存的原文快照。
          </p>
          <div class="source-list">
            <button
              v-for="k in knowledge"
              :key="k.id"
              @click="
                activeSource = k;
                showKnowledge = false;
              "
            >
              <FileText :size="16" />{{ k.title }}<small>{{ k.version }}</small>
            </button>
          </div>
          <details v-if="identity.role === 'ADMIN'">
            <summary>添加知识片段</summary>
            <form class="import-form" @submit.prevent="addKnowledge">
              <label
                >标题<input v-model="importForm.title" required maxlength="120"
              /></label>
              <div class="filter-row">
                <label
                  >主题<input
                    v-model="importForm.topic"
                    required
                    maxlength="40" /></label
                ><label
                  >版本<input
                    v-model="importForm.version"
                    required
                    maxlength="40"
                /></label>
              </div>
              <label
                >来源链接（可选）<input
                  v-model="importForm.sourceUrl"
                  type="url"
                  maxlength="500" /></label
              ><label
                >原文片段（最多 700 字）<textarea
                  v-model="importForm.text"
                  required
                  maxlength="700"
                ></textarea></label
              ><button class="primary-button" :disabled="busy || importing">
                保存片段
              </button>
            </form>
          </details>
          <button
            v-if="health.retrieval === 'qdrant' && identity.role === 'ADMIN'"
            class="outline-button"
            :disabled="busy"
            @click="
              run(async () => {
                await api('/knowledge/index', 'POST');
                notice = '向量索引同步完成';
              })
            "
          >
            <RefreshCw :size="16" />同步向量索引
          </button>
          <RetrievalDiagnostics v-if="latestMetric" :metric="latestMetric"
        /></template>
      </section>
    </div>
  </div>
</template>
