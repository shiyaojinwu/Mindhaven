import test from 'node:test';
import assert from 'node:assert/strict';
import { renderMarkdown } from '../.test-dist/views/chat/markdown.js';
const sources = [{ id: 'sleep-v1', title: '睡眠 <指南> "示例"' }];
test('renders headings, lists, emphasis, tables and fenced code', () => {
 const html = renderMarkdown('# 标题\n\n- **重点**\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```js\nconst x = 1;\n```');
 for (const tag of ['<h1>', '<ul>', '<strong>', '<table>', '<pre><code']) assert.ok(html.includes(tag));
});
test('citations retain buttons, unknown ids and escaped titles', () => {
 const html = renderMarkdown('**参考 [sleep-v1,missing]**', sources);
 assert.match(html, /data-source-index="0"/);
 assert.match(html, /\[missing\]/);
 assert.match(html, /&lt;指南&gt; &quot;示例&quot;/);
});
test('code, escaped citations and links are not converted into citation buttons', () => {
 const html = renderMarkdown('`[sleep-v1]`\n\n```\n[sleep-v1]\n```\n\n\\[sleep-v1]\n\n[sleep-v1](https://example.com)', sources);
 assert.ok(!html.includes('<button'));
 assert.match(html, /href="https:\/\/example.com"/);
});
test('untrusted HTML and dangerous links cannot execute, images do not load', () => {
 const html = renderMarkdown('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[x](javascript:alert(1))\n\n![image](https://example.com/tracker)');
 assert.ok(!/<script|<img|href="javascript:/i.test(html));
});
test('streaming partial syntax can be rerendered with final citations', () => {
 assert.doesNotThrow(() => renderMarkdown('**hello [sleep-'));
 assert.ok(!renderMarkdown('[sleep-v1]').includes('<button'));
 assert.ok(renderMarkdown('[sleep-v1]', sources).includes('<button'));
});

test('adjacent citations both remain clickable', () => {
 assert.equal((renderMarkdown('[sleep-v1][sleep-v1]', sources).match(/<button/g) ?? []).length, 2);
});
