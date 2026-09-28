import { test } from "node:test";
import assert from "node:assert/strict";
import { createRouter, createMemoryHistory } from "vue-router";
import { installAuthGuard, safeRedirect } from "../.test-dist/router/guards.js";
function fixture(identity) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/login", name: "login", component: {} },
      {
        path: "/home",
        name: "home",
        component: {},
        meta: { requiresAuth: true },
      },
      {
        path: "/chat",
        name: "chat",
        component: {},
        meta: { requiresAuth: true },
      },
      {
        path: "/admin",
        name: "admin",
        component: {},
        meta: { requiresAuth: true, adminOnly: true },
      },
    ],
  });
  installAuthGuard(
    router,
    async () => {},
    () => identity,
  );
  return router;
}
test("anonymous deep link is retained for login", async () => {
  const router = fixture(null);
  await router.push("/chat");
  assert.equal(router.currentRoute.value.name, "login");
  assert.equal(router.currentRoute.value.query.redirect, "/chat");
});
test("members cannot enter admin but admins can", async () => {
  const member = fixture({ role: "MEMBER" });
  await member.push("/admin");
  assert.equal(member.currentRoute.value.name, "home");
  const admin = fixture({ role: "ADMIN" });
  await admin.push("/admin");
  assert.equal(admin.currentRoute.value.name, "admin");
});
test("authenticated login redirects internally and rejects external URLs", async () => {
  const router = fixture({ role: "MEMBER" });
  await router.push("/login?redirect=%2Fchat");
  assert.equal(router.currentRoute.value.name, "chat");
  for (const value of [
    "//example.com",
    "https://example.com",
    "/login",
    "/unknown",
    ["//x"],
  ])
    assert.equal(safeRedirect(value), "/home");
});
