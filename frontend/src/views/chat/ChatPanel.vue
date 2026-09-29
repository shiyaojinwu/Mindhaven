<script setup lang="ts">
import { onMounted } from "vue";
import { RouterLink } from "vue-router";
import MessageContent from "./MessageContent.vue";
import MessageSources from "./MessageSources.vue";
import {
  Leaf,
  MessageCircle,
  Plus,
  Database,
  ArrowUpRight,
  ArrowUp,
} from "lucide-vue-next";
import type { Citation } from "../../types/knowledge.js";
import type { useChat } from "./useChat.js";
const props = defineProps<{ chat: ReturnType<typeof useChat>; mode: string }>();
const emit = defineEmits<{ source: [Citation]; knowledge: [] }>();
const {
  sessions,
  agentSteps,
  sessionId,
  messages,
  draft,
  sending,
  activeRun,
  error,
  status,
  runId,
  chatBox,
  openSession,
  newSession,
  send,
  stop,
  reconnect,
} = props.chat;
onMounted(() => {
  void props.chat.scroll(true);
});
const health = {
  get mode() {
    return props.mode;
  },
};
async function open(id: string) {
  try {
    await openSession(id);
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function startNew() {
  try {
    await newSession();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
</script>
<template>
  <div class="chat-layout">
    <aside class="conversation-list">
      <button class="outline-button" :disabled="sending" @click="startNew">
        <Plus :size="17" />开始新的对话</button
      ><span class="nav-label">最近的对话</span
      ><button
        v-for="s in sessions"
        :key="s.id"
        :disabled="sending"
        :class="['session-item', { selected: s.id === sessionId }]"
        @click="open(s.id)"
      >
        <MessageCircle :size="16" /><span>{{ s.title }}</span>
      </button>
      <p v-if="!sessions.length" class="muted small">
        你的第一段对话，会从这里开始。
      </p>
      <div class="chat-info">
        <Leaf :size="20" />
        <p>你可以按自己的节奏说。<br />不需要一次讲完。</p>
      </div>
    </aside>
    <section class="chat-window">
      <div class="chat-toolbar">
        <span><span class="online-dot"></span>心屿 · 倾听助手</span>
        <div class="chat-toolbar-actions">
          <button
            class="icon-button"
            :disabled="sending"
            aria-label="新建对话"
            @click="startNew"
          >
            <Plus :size="18" /></button
          ><button
            class="icon-button"
            aria-label="查看知识与检索设置"
            @click="emit('knowledge')"
          >
            <Database :size="18" />
          </button>
        </div>
      </div>
      <div class="mobile-session-picker">
        <label
          >历史对话<select
            :value="sessionId"
            :disabled="sending"
            @change="open(($event.target as HTMLSelectElement).value)"
          >
            <option value="" disabled>选择对话</option>
            <option v-for="s in sessions" :key="s.id" :value="s.id">
              {{ s.title }}
            </option>
          </select></label
        >
      </div>
      <div ref="chatBox" class="messages" aria-live="polite">
        <div v-if="!messages.length" class="chat-empty">
          <div class="assistant-emblem"><Leaf :size="32" /></div>
          <h2>你好，我在这里。</h2>
          <p>此刻有什么想说的吗？<br />一件小事、一点烦恼，都可以。</p>
          <div class="starter-prompts">
            <button
              v-for="q in [
                '最近学习压力很大',
                '总是担心别人怎么看我',
                '睡前脑子停不下来',
              ]"
              :key="q"
              @click="draft = q"
            >
              {{ q }}<ArrowUpRight :size="15" />
            </button>
          </div>
        </div>
        <div v-for="m in messages" :key="m.id" :class="['message', m.role]">
          <div v-if="m.role === 'assistant'" class="message-avatar">
            <Leaf :size="17" />
          </div>
          <div class="message-body">
            <span class="message-name">{{
              m.role === "assistant" ? "心屿" : "我"
            }}</span>
            <div class="bubble">
              <span v-if="!m.content && sending" class="thinking"
                >{{ status || "正在连接…" }}</span
              ><MessageContent
                :content="m.content"
                :sources="m.citations ?? []"
                @source="emit('source', $event)"
              />
            </div>
            <div
              v-if="m.recommendations?.length"
              class="recommendation-list"
              aria-label="本轮找到的课程与问卷"
            >
              <RouterLink
                v-for="item in m.recommendations"
                :key="item.kind + item.id"
                class="recommendation-card"
                :to="{
                  name: item.kind === 'course' ? 'courses' : 'survey',
                  query: { item: item.id },
                }"
              >
                <span class="tag">{{
                  item.kind === "course" ? "微课堂" : "问卷"
                }}</span>
                <strong>{{ item.title }}</strong>
                <span>{{ item.description }}</span>
                <span
                  >{{
                    item.kind === "course" ? "查看课程" : "查看问卷"
                  }}
                  →</span
                >
              </RouterLink>
            </div>
            <MessageSources
              v-if="m.role === 'assistant'"
              :message="m"
              @source="emit('source', $event)"
            />
            <span v-if="m.status === 'partial'" class="failed-label">本轮仅完成部分内容，可继续</span>
            <span v-if="m.status === 'failed'" class="failed-label"
              >本轮未完成，不会注入后续上下文</span
            >
          </div>
        </div>
      </div>
      <details v-if="agentSteps.length" class="agent-progress" :open="activeRun">
        <summary>本轮执行过程 · {{ agentSteps.length }} 个阶段</summary>
        <ol>
          <li v-for="(item, index) in agentSteps" :key="item.sequence ?? index"
              :class="{ 'current-stage': activeRun && index === agentSteps.length - 1 }">
            <span>第 {{ item.step }} 步</span> {{ item.label }}
          </li>
        </ol>
      </details>
      <div class="chat-run-status" role="status">
        <span>{{ status }}</span>
        <button v-if="activeRun" class="text-button" @click="stop">
          停止生成
        </button>
        <button
          v-else-if="runId && error"
          class="text-button"
          @click="reconnect"
        >
          恢复连接
        </button>
      </div>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <form class="composer" @submit.prevent="send">
        <label class="sr-only" for="chat-draft">想说的话</label
        ><textarea
          id="chat-draft"
          v-model="draft"
          maxlength="1500"
          placeholder="慢慢说，我在听…"
          :disabled="sending"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <div class="composer-actions">
          <span
            >{{
              health.mode === "demo"
                ? "演示回复 · 非真实模型"
                : "AI 回复可能有误，请结合实际判断"
            }}<small>{{ draft.length }} / 1500</small></span
          ><button
            class="send-button"
            :disabled="sending || activeRun || !draft.trim()"
            aria-label="发送消息"
          >
            <ArrowUp :size="22" />
          </button>
        </div>
      </form>
      <p class="chat-disclaimer">
        如有即时安全危险，请联系当地急救服务和身边可信任的人。本应用无法提供紧急救援。
      </p>
    </section>
  </div>
</template>

<style scoped>
.agent-progress {
  margin: 0 20px 8px;
  color: var(--muted, #586b61);
  font-size: 13px;
}
.agent-progress summary { cursor: pointer; }
.agent-progress ol { margin: 8px 0; padding-left: 22px; max-height: 140px; overflow-y: auto; }
.agent-progress li { padding: 3px 0; }
.agent-progress li span { margin-right: 8px; }
.current-stage { color: var(--text, #253e32); font-weight: 600; }

.recommendation-list {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}
.recommendation-card {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid var(--line, #dce5e0);
  border-radius: 12px;
  color: inherit;
  text-decoration: none;
  background: var(--surface, #fff);
}
.recommendation-card > span {
  font-size: 13px;
}
.recommendation-card:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}
</style>
