export interface SseEvent {
  name: string;
  sequence: string | null;
  data: unknown;
}

/** Incremental parser: a network chunk need not end at a line or event boundary. */
export class SseParser {
  private buffer = "";
  private previousCR = false;

  feed(chunk: string): SseEvent[] {
    for (const character of chunk) {
      if (character === "\n" && this.previousCR) {
        this.previousCR = false;
        continue;
      }
      this.previousCR = character === "\r";
      this.buffer += character === "\r" ? "\n" : character;
    }
    const events: SseEvent[] = [];
    let end: number;
    while ((end = this.buffer.indexOf("\n\n")) >= 0) {
      const block = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      let name = "message";
      let sequence: string | null = null;
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line.startsWith(":")) continue;
        const separator = line.indexOf(":");
        const field = separator < 0 ? line : line.slice(0, separator);
        const value =
          separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "");
        if (field === "event") name = value;
        if (field === "id") {
          if (!/^\d{1,20}(-\d{1,20})?$/.test(value))
            throw new Error("流式事件序号无效，请重新连接");
          sequence = value;
        }
        if (field === "data") data.push(value);
      }
      if (
        data.length &&
        [
          "snapshot-required",
          "transport-error",
          "stream-start",
          "sources",
          "delta",
          "answer-reset",
          "done",
          "error",
          "terminal",
          "agent-status",
          "recommendations",
        ].includes(name)
      )
        events.push({ name, sequence, data: JSON.parse(data.join("\n")) });
    }
    if (this.buffer.length > 2_000_000)
      throw new Error("流式事件过大，请重新连接");
    return events;
  }
}

/** Redis IDs are decimal pairs, not floating-point JS numbers. Supports legacy numeric IDs. */
export function compareEventIds(left: string, right: string): number {
  const [a, b = "0"] = left.split("-");
  const [c, d = "0"] = right.split("-");
  if (BigInt(a) !== BigInt(c)) return BigInt(a) < BigInt(c) ? -1 : 1;
  return BigInt(b) === BigInt(d) ? 0 : BigInt(b) < BigInt(d) ? -1 : 1;
}
