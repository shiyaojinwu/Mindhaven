import { api, type Citation, type Message, type Metric } from "../../api";
export interface ChatRun {
  id: string;
  sessionId: string;
  requestId: string;
  message: string;
  status:
    | "QUEUED"
    | "RUNNING"
    | "COMPLETED"
    | "FAILED"
    | "CANCELLED"
    | "INTERRUPTED";
  error: string | null;
}
export interface ChatCommand {
  message: string;
  topic: string;
  version: string;
  rewrite: boolean;
  requestId: string;
}
export type RunEvent =
  | { name: "delta"; data: { text: string } }
  | { name: "sources"; data: Citation[] }
  | { name: "done"; data: { message: Message; metrics: Metric } }
  | { name: "error"; data: { message: string } }
  | { name: "terminal"; data: ChatRun };
export const createRun = (session: string, body: ChatCommand) =>
  api<ChatRun>(`/sessions/${session}/runs`, "POST", body);
export const recentRuns = (session: string) =>
  api<ChatRun[]>(`/sessions/${session}/runs`);
export const cancelRun = (id: string) =>
  api<ChatRun>(`/runs/${id}/cancel`, "POST");
export async function subscribeRun(
  id: string,
  after: number,
  signal: AbortSignal,
  consume: (event: RunEvent, sequence: number | null) => void,
) {
  const response = await fetch(`/api/runs/${id}/events?after=${after}`, {
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
  let buffer = "",
    terminal = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, "\n");
      let end: number;
      while ((end = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        let name = "message",
          sequence: number | null = null;
        const data: string[] = [];
        for (const line of block.split("\n")) {
          if (line.startsWith("event:")) name = line.slice(6).trim();
          if (line.startsWith("id:")) sequence = Number(line.slice(3));
          if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
        }
        if (
          data.length &&
          ["sources", "delta", "done", "error", "terminal"].includes(name)
        ) {
          consume(
            { name, data: JSON.parse(data.join("\n")) } as RunEvent,
            sequence,
          );
          if (name === "terminal") terminal = true;
        }
      }
      if (done) break;
    }
    if (!terminal) throw new Error("连接中断，正在恢复原任务…");
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
