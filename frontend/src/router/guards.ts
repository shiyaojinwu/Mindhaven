import type { Router } from "vue-router";
import type { Identity } from "../types/auth.js";
export function safeRedirect(value: unknown): string {
  return typeof value === "string" &&
    /^\/(home|chat|survey|courses|reports|posts|admin)(?:[?#].*)?$/.test(value)
    ? value
    : "/home";
}
export function installAuthGuard(
  router: Router,
  restore: () => Promise<void>,
  current: () => Identity | null,
) {
  return router.beforeEach(async (to) => {
    await restore();
    const identity = current();
    if (to.meta.requiresAuth && !identity)
      return { name: "login", query: { redirect: to.fullPath } };
    if (to.meta.adminOnly && identity?.role !== "ADMIN")
      return { name: "home" };
    if (to.name === "login" && identity) return safeRedirect(to.query.redirect);
  });
}
