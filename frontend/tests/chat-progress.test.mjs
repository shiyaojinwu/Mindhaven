import { test } from 'node:test';
import assert from 'node:assert/strict';
import { useChat } from '../.test-dist/views/chat/useChat.js';
const event = (seq, name, data) => `id: ${seq}\nevent: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
test('progress updates before done and retains batched stages', async t => {
  let stream;
  const body = new ReadableStream({ start(c) { stream = c; } });
  const run = { id: 'run', sessionId: 'session', message: '找课程', status: 'RUNNING' };
  t.mock.method(globalThis, 'fetch', async url => url.includes('/events?') ? new Response(body) : new Response(JSON.stringify(url.endsWith('/runs') ? run : [])));
  const chat = useChat();
  chat.sessionId.value = 'session'; chat.draft.value = '找课程';
  const sending = chat.send();
  await tick();
  const send = text => stream.enqueue(new TextEncoder().encode(text));
  send(event(1, 'agent-status', { phase: 'model', label: '理解中', step: 1 }));
  await tick();
  assert.equal(chat.status.value, '理解中');
  assert.equal(chat.activeRun.value, true);
  send(event(10, 'delta', { text: '临时说明' }));
  await tick();
  assert.equal(chat.messages.value.at(-1).content, '临时说明');
  send(event('10-1', 'agent-status', { phase: 'draft', label: '中间草稿', step: 1, draft: '临时说明' }) + event(11, 'answer-reset', {}));
  await tick();
  assert.equal(chat.messages.value.at(-1).content, '');
  assert.equal(chat.messages.value.at(-1).execution.at(-1).draft, '临时说明');
  assert.deepEqual(chat.messages.value.at(-1).execution.map(s => s.label), ['理解中', '中间草稿']);
  send(event(12, 'agent-status', { phase: 'tool', label: '查课程', step: 1 }) + event(13, 'done', { message: { id: 'reply', role: 'assistant', content: '结果', status: 'complete' }, metrics: { id: 'metric' } }) + event(14, 'terminal', { ...run, status: 'COMPLETED' }));
  stream.close(); await sending;
  assert.equal(chat.status.value, '回复已完成');
  assert.deepEqual(chat.agentSteps.value.map(s => s.label), ['理解中', '中间草稿', '查课程']);
  assert.equal(chat.activeRun.value, false);
});

test('continue task posts once and renders resumed stream', async t => {
  let requests = 0;
  const run = { id: 'child', sessionId: 'session', message: '继续完成原任务', status: 'RUNNING' };
  const message = { id: 'answer2', role: 'assistant', content: '第二部分', status: 'complete', citations: [] };
  t.mock.method(globalThis, 'fetch', async url => {
    if (url.endsWith('/parent/continue')) { requests++; await tick(); return new Response(JSON.stringify(run)); }
    if (url.includes('/events?')) return new Response(event(1, 'delta', { text: '第二部分' }) + event(2, 'done', { message, metrics: {} }) + event(3, 'terminal', { ...run, status: 'COMPLETED' }));
    if (url.endsWith('/messages')) return new Response(JSON.stringify([message]));
    return new Response('[]');
  });
  const chat = useChat();
  chat.sessionId.value = 'session'; chat.runId.value = 'parent';
  chat.messages.value = [{ id: 'answer1', role: 'assistant', content: '第一部分', status: 'partial', citations: [] }];
  await Promise.all([chat.continueTask(), chat.continueTask()]);
  assert.equal(requests, 1);
  assert.equal(chat.runId.value, 'child');
  assert.equal(chat.messages.value.at(-1).content, '第二部分');
  assert.equal(chat.activeRun.value, false);
});

test('Redis reconnect resumes after processed ID and replays reset without duplicated text', async t => {
  const run = { id: 'redis-run', sessionId: 's', message: 'hello', status: 'RUNNING' };
  const message = { id: 'final', role: 'assistant', content: 'new', status: 'complete', citations: [] };
  let subscriptions = 0, posts = 0;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (url.includes('/events?')) {
      subscriptions++;
      if (subscriptions === 1) return new Response(event('1790750000000-9', 'delta', {text:'old'}));
      assert.ok(url.endsWith('after=1790750000000-9'));
      return new Response(event('1790750000000-9', 'delta', {text:'old'}) + event('1790750000000-10','answer-reset',{})
        + event('1790750000000-11','delta',{text:'new'}) + event('1790750000000-12','done',{message,metrics:{}})
        + event('1790750000000-13','terminal',{...run,status:'COMPLETED'}));
    }
    if (options?.method === 'POST') { posts++; return new Response(JSON.stringify(run)); }
    if (url.endsWith('/redis-run')) return new Response(JSON.stringify(run));
    if (url.endsWith('/messages')) return new Response(JSON.stringify([message]));
    return new Response('[]');
  });
  const chat = useChat(); chat.sessionId.value='s'; chat.draft.value='hello';
  await chat.send();
  assert.equal(posts,1); assert.equal(subscriptions,2);
  assert.equal(chat.messages.value.at(-1).content,'new');
});

test('expired stream recovers authoritative answer even without Redis terminal', async t => {
  const run={id:'expired',sessionId:'s',message:'hi',status:'RUNNING'};
  const message={id:'saved',role:'assistant',content:'database answer',status:'complete',citations:[]};
  t.mock.method(globalThis,'fetch',async (url,options) => {
    if(url.includes('/events?')) return new Response('event: snapshot-required\ndata: {}\n\n');
    if(options?.method==='POST') return new Response(JSON.stringify(run));
    if(url.endsWith('/expired')) return new Response(JSON.stringify({...run,status:'COMPLETED'}));
    if(url.endsWith('/messages')) return new Response(JSON.stringify([message]));
    return new Response('[]');
  });
  const chat=useChat(); chat.sessionId.value='s'; chat.draft.value='hi'; await chat.send();
  assert.equal(chat.messages.value.at(-1).content,'database answer');
  assert.equal(chat.activeRun.value,false);
});
