import { compareEventIds } from "../../utils/sse.js";
import type { ChatCommand, ChatRun } from "../../types/chat.js";
import { ref, nextTick, onBeforeUnmount } from "vue";
import {
  listSessions,
  createSession,
  getMessages,
  listMetrics,
} from "../../api/chat.js";
import type { Session } from "../../types/chat.js";
import type { Message } from "../../types/chat.js";
import type { Metric } from "../../types/chat.js";
import {
  createRun,
  recentRuns,
  cancelRun,
  continueRun,
  subscribeRun,
  getRun,
} from "../../api/chat.js";

export function useChat() {
  const sessions = ref<Session[]>([]),
    sessionId = ref(""),
    messages = ref<Message[]>([]);
  const metrics = ref<Metric[]>([]),
    draft = ref(""),
    topic = ref("全部"),
    version = ref("v1");
  const agentSteps = ref<{ sequence: string | null; phase: string; label: string; step: number }[]>([]);
  const activeRun = ref(false);
  const sending = ref(false),
    error = ref(""),
    status = ref(""),
    runId = ref("");
  const chatBox = ref<HTMLElement | null>(null);
  let revision = 0,
    controller: AbortController | null = null;
  let pending: { session: string; command: ChatCommand } | null = null;
  const terminal = (run: ChatRun) =>
    !["RUNNING", "QUEUED"].includes(run.status);
  async function scroll(force = false) {
    const element = chatBox.value;
    const follows =
      !element ||
      element.scrollHeight - element.scrollTop - element.clientHeight < 140;
    await nextTick();
    if (force || follows)
      chatBox.value?.scrollTo({
        top: chatBox.value.scrollHeight,
        behavior: "auto",
      });
  }
  async function loadSessions() {
    sessions.value = await listSessions();
  }
  function detach() {
    revision++;
    controller?.abort();
    controller = null;
    sending.value = false;
    activeRun.value = false;
  }
  async function attach(run: ChatRun, generation: number) {
    runId.value = run.id;
    agentSteps.value = [];
    activeRun.value = !terminal(run);
    sending.value = !terminal(run);
    status.value = "正在连接…";
    const last = messages.value.at(-1);
    if (!(last?.role === "user" && last.content === run.message))
      messages.value.push({
        id: `${run.id}-user`,
        role: "user",
        content: run.message,
        status: "pending",
        citations: [],
      });
    const partial: Message = {
      id: `${run.id}-assistant`,
      role: "assistant",
      content: "",
      status: "pending",
      citations: [],
    };
    messages.value.push(partial);
    let cursor = "0",
      ended = false,
      needsSnapshot = false;
    controller = new AbortController();
    const signal = controller.signal;
    for (
      let attempt = 0;
      attempt < 36 && !ended && generation === revision;
      attempt++
    ) {
      try {
        await subscribeRun(run.id, cursor, signal, (event, sequence) => {
          if (
            generation !== revision ||
            (sequence !== null && compareEventIds(sequence, cursor) <= 0)
          )
            return;
          if (event.name === "snapshot-required") { needsSnapshot = true; throw new Error("事件已过期，正在加载保存的结果…"); }
          if (event.name === "transport-error") throw new Error(event.data.message);
          const current = messages.value.find((m) => m.id === partial.id);
          if (event.name === "agent-status") {
            status.value = event.data.label;
            agentSteps.value.push({ ...event.data, sequence });
            if (current) (current.execution ??= []).push(event.data);
          }
          if (event.name === "recommendations" && current)
            current.recommendations = event.data;
          if (event.name === "sources" && current)
            current.citations = event.data;
          if (event.name === "answer-reset" && current) current.content = "";
          if (event.name === "delta" && current) {
            current.content += event.data.text;
            error.value = "";
            status.value = "正在回复…";
          }
          if (event.name === "done") {
            const index = messages.value.findIndex((m) => m.id === partial.id);
            if (index >= 0) messages.value[index] = {
              ...event.data.message,
              execution: event.data.message.execution ?? messages.value[index]?.execution,
            };
            metrics.value = [
              event.data.metrics,
              ...metrics.value.filter((m) => m.id !== event.data.metrics.id),
            ];
            status.value = event.data.message.status === "partial" ? "已保留部分回复，可继续" : "回复已完成";
            error.value = "";
            pending = null;
          }
          if (event.name === "error") {
            error.value = event.data.message;
            if (current) current.status = "failed";
          }
          if (event.name === "terminal") {
            ended = true;
            sending.value = false;
            activeRun.value = false;
            pending = null;
            status.value =
              event.data.status === "COMPLETED"
                ? (messages.value.at(-1)?.status === "partial" ? "已保留部分回复，可继续" : "回复已完成")
                : event.data.error || "本次任务已结束";
            if (current && event.data.status !== "COMPLETED")
              current.status = "failed";
          }
          if (sequence !== null) cursor = sequence;
          void scroll();
        });
      } catch (e) {
        if (signal.aborted || generation !== revision) return;
        status.value = "连接中断，正在恢复原任务…";
        // Recovery reads the authoritative result even when Redis/outbox cannot deliver terminal.
        // This is an outage-only fallback, not the normal live event transport.
        try {
          const currentRun = await getRun(run.id);
          if (signal.aborted || generation !== revision) return;
          if (needsSnapshot && !terminal(currentRun)) {
            const history = await getMessages(run.sessionId);
            if (signal.aborted || generation !== revision) return;
            messages.value = [...history, { ...partial, content: "", status: "pending" }];
            agentSteps.value = [];
            cursor = "0";
            needsSnapshot = false;
            status.value = "实时片段不完整，已加载保存记录，等待任务结果…";
          }
          if (terminal(currentRun)) {
            const history = await getMessages(run.sessionId);
            if (signal.aborted || generation !== revision) return;
            messages.value = history;
            ended = true;
            sending.value = false;
            activeRun.value = false;
            pending = null;
            status.value = currentRun.status === "COMPLETED"
              ? (history.at(-1)?.status === "partial" ? "已保留部分回复，可继续" : "回复已完成")
              : currentRun.error || "本次任务已结束";
            error.value = currentRun.status === "COMPLETED" ? "" : currentRun.error || "任务已结束";
            break;
          }
        } catch { /* Bounded transport retry below; never submit another model run. */ }
        if (attempt === 35)
          error.value = "连接暂时不可用，可点击恢复连接；不会重新生成回答。";
        else
          await new Promise((resolve) =>
            setTimeout(resolve, Math.min(5000, 600 * (attempt + 1))),
          );
      }
    }
    if (generation !== revision) return;
    if (!ended) {
      status.value = "连接已断开，任务状态待确认";
      sending.value = false;
      return;
    }
    // Auxiliary refresh failures cannot turn a completed response into a failed send.
    try {
      const history = await getMessages(run.sessionId);
      if (
        generation === revision &&
        run.sessionId === sessionId.value &&
        status.value === "回复已完成"
      )
        messages.value = history;
    } catch {
      /* Keep the authoritative done event rendered. */
    }
    void loadSessions().catch(() => {});
  }
  async function openSession(id: string) {
    detach();
    const generation = revision;
    error.value = "";
    status.value = "";
    runId.value = "";
    agentSteps.value = [];
    const [history, runs] = await Promise.all([
      getMessages(id),
      recentRuns(id),
    ]);
    if (generation !== revision) return;
    sessionId.value = id;
    messages.value = history;
    window.sessionStorage.setItem("mindhaven:session", id);
    await scroll(true);
    const latest = runs[0];
    if (latest) runId.value = latest.id;
    if (latest && latest.status !== "COMPLETED")
      void attach(latest, generation);
  }
  async function newSession() {
    detach();
    pending = null;
    error.value = "";
    status.value = "";
    runId.value = "";
    agentSteps.value = [];
    const session = await createSession();
    sessions.value.unshift(session);
    sessionId.value = session.id;
    messages.value = [];
    window.sessionStorage.setItem("mindhaven:session", session.id);
  }
  async function send() {
    const text = draft.value.trim();
    if (!text || sending.value || activeRun.value) return;
    error.value = "";
    sending.value = true;
    try {
      if (!sessionId.value) await newSession();
      const signature = {
        message: text,
        topic: topic.value,
        version: version.value,
        rewrite: true,
      };
      if (
        !pending ||
        pending.session !== sessionId.value ||
        JSON.stringify({ ...pending.command, requestId: undefined }) !==
          JSON.stringify(signature)
      )
        pending = {
          session: sessionId.value,
          command: { ...signature, requestId: crypto.randomUUID() },
        };
      sending.value = true;
      const run = await createRun(pending.session, pending.command);
      draft.value = "";
      if (run.status === "COMPLETED") {
        pending = null;
        await openSession(run.sessionId);
        status.value = "回复已完成";
        void listMetrics()
          .then((value) => {
            metrics.value = value;
          })
          .catch(() => {});
        return;
      }
      detach();
      const generation = revision;
      await attach(run, generation);
    } catch (e) {
      error.value = (e as Error).message;
      sending.value = false;
    }
  }
  async function continueTask() {
    if (!runId.value || sending.value || activeRun.value) return;
    const generation = revision;
    sending.value = true;
    error.value = "";
    try {
      const run = await continueRun(runId.value);
      if (generation !== revision) return;
      if (terminal(run)) { await openSession(run.sessionId); return; }
      detach();
      await attach(run, revision);
    } catch (e) {
      if (generation === revision) {
        error.value = (e as Error).message;
        sending.value = false;
      }
    }
  }
  async function stop() {
    if (!runId.value) return;
    try {
      const stopped = await cancelRun(runId.value);
      activeRun.value = !terminal(stopped);
      sending.value = false;
      status.value =
        stopped.status === "COMPLETED" ? "回复已完成" : "任务已停止";
      if (stopped.status === "COMPLETED") await openSession(stopped.sessionId);
    } catch (e) {
      error.value = (e as Error).message;
    }
  }
  async function reconnect() {
    if (sessionId.value) await openSession(sessionId.value);
  }
  onBeforeUnmount(detach);
  return {
    sessions,
    agentSteps,
    sessionId,
    messages,
    metrics,
    draft,
    topic,
    version,
    sending,
    activeRun,
    error,
    status,
    runId,
    chatBox,
    loadSessions,
    openSession,
    newSession,
    send,
    stop,
    reconnect,
    continueTask,
    scroll,
  };
}
