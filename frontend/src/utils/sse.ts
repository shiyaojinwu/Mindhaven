export interface SseEvent {
  name: string;
  sequence: number | null;
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
      let sequence: number | null = null;
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line.startsWith(":")) continue;
        const separator = line.indexOf(":");
        const field = separator < 0 ? line : line.slice(0, separator);
        const value =
          separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "");
        if (field === "event") name = value;
        if (field === "id") {
          const number = Number(value);
          if (!/^\d+$/.test(value) || !Number.isSafeInteger(number))
            throw new Error("流式事件序号无效，请重新连接");
          sequence = number;
        }
        if (field === "data") data.push(value);
      }
      if (
        data.length &&
        ["sources", "delta", "done", "error", "terminal"].includes(name)
      )
        events.push({ name, sequence, data: JSON.parse(data.join("\n")) });
    }
    if (this.buffer.length > 2_000_000)
      throw new Error("流式事件过大，请重新连接");
    return events;
  }
}
