import { SseParser } from "../utils/sse.js";
import { api } from "./http.js";
import type { Citation } from "../types/knowledge.js";
import type { Message } from "../types/chat.js";
import type { Metric } from "../types/chat.js";
import type { ChatRun, ChatCommand, RunEvent } from "../types/chat.js";
export const createRun = (session: string, body: ChatCommand) =>
  api<ChatRun>(`/sessions/${session}/runs`, "POST", body);
export const recentRuns = (session: string) =>
  api<ChatRun[]>(`/sessions/${session}/runs`);
export const continueRun = (id: string) =>
  api<ChatRun>(`/runs/${id}/continue`, "POST");
export const cancelRun = (id: string) =>
  api<ChatRun>(`/runs/${id}/cancel`, "POST");
export async function subscribeRun(
  id: string,
  after: string,
  signal: AbortSignal,
  consume: (event: RunEvent, sequence: string | null) => void,
) {
  const response = await fetch(`/api/runs/${id}/events?after=${encodeURIComponent(after)}`, {
    signal,
    headers: { Accept: "text/event-stream" },
  });
  if (!response.ok) {
    if (response.status === 401)
      window.dispatchEvent(new Event("mindhaven:session-expired"));
    throw new Error("连接暂时不可用，任务仍可在历史对话中查看");
  }
  if (!response.body) throw new Error("浏览器不支持流式响应");
  const reader = response.body.getReader(),
    decoder = new TextDecoder();
  const parser = new SseParser();
  let terminal = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      for (const event of parser.feed(
        decoder.decode(value, { stream: !done }),
      )) {
        consume(
          { name: event.name, data: event.data } as RunEvent,
          event.sequence,
        );
        if (event.name === "terminal") terminal = true;
      }
      if (done) break;
    }
    if (!terminal) throw new Error("连接中断，正在恢复原任务…");
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

import type { Session } from "../types/chat.js";
export const listSessions = () => api<Session[]>("/sessions");
export const createSession = () => api<Session>("/sessions", "POST");
export const getMessages = (id: string) =>
  api<Message[]>(`/sessions/${id}/messages`);
export const listMetrics = () => api<Metric[]>("/metrics");

export const getRun = (id: string) => api<ChatRun>(`/runs/${id}`);
