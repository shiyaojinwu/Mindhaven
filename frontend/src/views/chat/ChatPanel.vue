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
  continueTask,
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
            <details v-if="m.execution?.length" class="agent-progress" :open="m.status === 'pending'">
              <summary>
                执行过程 · {{ m.execution.length }} 个阶段
                <span class="execution-outcome">{{ m.status === 'pending' ? '进行中' : m.status === 'complete' ? '已完成' : m.status === 'partial' ? '部分完成' : '未完成' }}</span>
              </summary>
              <ol>
                <li v-for="(item, index) in m.execution" :key="index"
                    :class="{ 'current-stage': m.status === 'pending' && index === m.execution.length - 1 }">
                  <div class="stage-heading">
                    <span>{{ index + 1 }}. {{ item.phase === 'draft' ? '中间草稿' : item.phase === 'tool' ? '调用工具' : item.phase === 'model' ? '模型响应' : '整理上下文与结果' }}</span>
                    <small>第 {{ item.step }} 轮</small>
                  </div>
                  <span>{{ m.status === 'pending' && index === m.execution.length - 1 ? item.label : item.label.replace(/^正在/, '').replace(/[…。.]+$/, '') }}</span>
                  <details v-if="item.draft" class="draft-detail">
                    <summary>查看保留的文字（非最终答案）</summary>
                    <div class="draft-text">{{ item.draft }}</div>
                  </details>
                  <details v-if="item.toolName" class="tool-detail">
                    <summary>{{ item.toolName }} · 调用参数</summary>
                    <pre>{{ item.arguments || '{}' }}</pre>
                  </details>
                </li>
              </ol>
              <p v-if="m.status !== 'pending'" class="execution-finish">
                {{ m.status === 'complete' ? '✓ 本轮回复已完成' : m.status === 'partial' ? '本轮部分完成，可继续任务' : '本轮已结束，未全部完成' }}
              </p>
            </details>
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
            <button v-if="m.status === 'partial' && m === messages.at(-1) && runId"
              type="button" :disabled="sending || activeRun" @click="continueTask">继续任务</button>
            <span v-if="m.status === 'failed'" class="failed-label"
              >本轮未完成，不会注入后续上下文</span
            >
          </div>
        </div>
      </div>
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
          rows="1"
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
/* Keep execution history inside each message, never above the composer. */
.agent-progress { margin: 4px 0 8px; padding: 7px 10px; border: 1px solid var(--line, #e8dfe7); border-radius: 9px; color: var(--muted, #817487); font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.agent-progress summary { cursor: pointer; }
.execution-outcome { display: inline-block; margin-left: 6px; font-size: 11px; }
.agent-progress ol { list-style: none; margin: 6px 0 0; padding: 0; }
.agent-progress li { padding: 5px 0; border-top: 1px solid var(--line, #eee8ee); }
.stage-heading { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; }
.stage-heading small { font-weight: normal; }
.current-stage { color: var(--text, #514554); font-weight: 600; }
.draft-detail { margin-top: 4px; font-weight: normal; }
.draft-text { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 6px; padding: 8px; border-left: 2px solid #d7c6d3; background: #faf7fa; max-height: 220px; overflow: auto; }
.tool-detail { margin-top: 3px; font-weight: normal; }
.tool-detail pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 160px; overflow: auto; }
.execution-finish { margin: 6px 0 0; font-weight: 500; }
.chat-toolbar, .mobile-session-picker, .composer, .chat-run-status, .chat-disclaimer { flex-shrink: 0; }
.messages { min-height: 160px; overscroll-behavior: contain; }
.chat-run-status { padding: 6px 20px; font-size: 12px; }
.chat-run-status:empty { display: none; }
.composer textarea { min-height: 52px; max-height: 150px; field-sizing: content; }
@media (max-width: 760px) {
  .chat-layout { height: max(560px, calc(100dvh - 180px)); min-height: 560px; }
  .messages { padding: 16px 12px; }
  .message-body { min-width: 0; }
  .agent-progress { padding: 6px 8px; }
}

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
