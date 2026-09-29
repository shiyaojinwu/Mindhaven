import { test } from "node:test";
import assert from "node:assert/strict";
import { SseParser } from "../.test-dist/utils/sse.js";

test("arbitrary packet boundaries retain Unicode text and event sequence", () => {
  const parser = new SseParser();
  const source = 'id: 12\r\nevent: delta\r\ndata: {"text":"你好🌸"}\r\n\r\n';
  const events = [...source].flatMap((character) => parser.feed(character));
  assert.deepEqual(events, [
    { name: "delta", sequence: 12, data: { text: "你好🌸" } },
  ]);
});

test("heartbeat and unknown events do not become messages", () => {
  const parser = new SseParser();
  assert.deepEqual(
    parser.feed(": keepalive\n\nevent: future\ndata: ignored\n\n"),
    [],
  );
});

test("multiline JSON and multiple events support CR-only line endings", () => {
  const parser = new SseParser();
  const events = parser.feed(
    'id: 1\revent: delta\rdata: {"text":\rdata: "hi"}\r\revent: terminal\rdata: {}\r\r',
  );
  assert.equal(events.length, 2);
  assert.equal(events[0].data.text, "hi");
  assert.equal(events[1].sequence, null);
  assert.equal(events[1].name, "terminal");
});

test("invalid IDs and JSON fail rather than advance the reconnect cursor", () => {
  assert.throws(() =>
    new SseParser().feed("id: NaN\nevent: delta\ndata: {}\n\n"),
  );
  assert.throws(() =>
    new SseParser().feed("id: 9007199254740993\nevent: delta\ndata: {}\n\n"),
  );
  assert.throws(() => new SseParser().feed("event: delta\ndata: broken\n\n"));
});

test("partial events are withheld until complete and unbounded fragments are rejected", () => {
  const parser = new SseParser();
  assert.deepEqual(parser.feed('event: delta\ndata: {"text":"ok"}\n'), []);
  assert.equal(parser.feed("\n")[0].data.text, "ok");
  assert.throws(() => new SseParser().feed("x".repeat(2_000_001)));
});

test("agent progress and recommendation events retain replay sequence", () => {
  const parser = new SseParser();
  const events = parser.feed(
    'id: 7\nevent: agent-status\ndata: {"phase":"tool","label":"正在查找课程","step":1}\n\nid: 8\nevent: recommendations\ndata: [{"kind":"course","id":"course-1","title":"睡眠","description":"练习"}]\n\n',
  );
  assert.deepEqual(
    events.map((e) => [e.name, e.sequence]),
    [
      ["agent-status", 7],
      ["recommendations", 8],
    ],
  );
  assert.equal(events[1].data[0].id, "course-1");
});
