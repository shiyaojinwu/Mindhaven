import { test } from "node:test";
import assert from "node:assert/strict";
import { effectScope, nextTick, ref } from "vue";
import { useCourses } from "../.test-dist/views/courses/useCourses.js";
import { useReports } from "../.test-dist/views/reports/useReports.js";
import { usePosts } from "../.test-dist/views/posts/usePosts.js";
import { useKnowledge } from "../.test-dist/views/knowledge/useKnowledge.js";

const run = (action) => action();
const response = (value) =>
  new Response(JSON.stringify(value), { status: 200 });
function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

test("course completion cannot close a different course opened during the request", async (t) => {
  const pending = deferred();
  t.mock.method(globalThis, "fetch", () => pending.promise);
  const courses = useCourses(run, ref(""));
  courses.activeCourse.value = { id: "a" };
  const saving = courses.finishCourse();
  courses.activeCourse.value = { id: "b" };
  pending.resolve(response({}));
  await saving;
  assert.deepEqual(courses.completed.value, ["a"]);
  assert.equal(courses.activeCourse.value.id, "b");
});

test("an older cached report response cannot overwrite a newly generated analysis", async (t) => {
  const pending = deferred();
  t.mock.method(globalThis, "fetch", (_url, options) =>
    options.method === "POST"
      ? Promise.resolve(response({ mode: "demo", content: "new" }))
      : pending.promise,
  );
  const scope = effectScope();
  t.after(() => scope.stop());
  const reports = scope.run(() => useReports(run));
  reports.activeReport.value = { id: "a" };
  await nextTick();
  await reports.analyzeReport();
  pending.resolve(response({ mode: "demo", content: "old" }));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(reports.reportAnalysis.value.content, "new");
});

test("post create, hug and delete apply server responses to the local list", async (t) => {
  const replies = [{ id: "a", hugs: 0 }, { id: "a", hugs: 1 }, {}];
  t.mock.method(globalThis, "fetch", () =>
    Promise.resolve(response(replies.shift())),
  );
  const posts = usePosts(run, ref(""));
  posts.postText.value = "今天不错";
  await posts.publishPost();
  assert.equal(posts.postText.value, "");
  await posts.hug("a");
  assert.equal(posts.posts.value[0].hugs, 1);
  await posts.removePost("a");
  assert.deepEqual(posts.posts.value, []);
});

test("knowledge save refreshes only knowledge and preserves form on a failed save", async (t) => {
  const paths = [];
  t.mock.method(globalThis, "fetch", (url, options) => {
    paths.push([url, options.method]);
    return Promise.resolve(
      response(options.method === "POST" ? { id: "a" } : [{ id: "a" }]),
    );
  });
  const knowledge = useKnowledge(run, ref(""), ref({ retrieval: "qdrant" }));
  knowledge.importForm.value.title = "title";
  await knowledge.addKnowledge();
  assert.deepEqual(paths, [
    ["/api/knowledge", "POST"],
    ["/api/knowledge", "GET"],
  ]);
  assert.equal(knowledge.knowledge.value.length, 1);
  globalThis.fetch = () => Promise.reject(new Error("offline"));
  knowledge.importForm.value.title = "keep this";
  await assert.rejects(knowledge.addKnowledge(), /offline/);
  assert.equal(knowledge.importForm.value.title, "keep this");
  assert.equal(knowledge.importing.value, false);
});
