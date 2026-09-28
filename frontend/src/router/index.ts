import { createRouter, createWebHashHistory } from "vue-router";
import { useAuth } from "../composables/useAuth.js";
import { installAuthGuard } from "./guards.js";
// Preserve old bookmarks such as #chat when adopting standard hash routes.
if (
  /^#(?:home|chat|survey|courses|reports|posts|admin)$/.test(
    window.location.hash,
  )
) {
  window.history.replaceState(null, "", "#/" + window.location.hash.slice(1));
}
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("../views/auth/LoginView.vue"),
    },
    {
      path: "/",
      component: () => import("../layouts/WorkspaceLayout.vue"),
      meta: { requiresAuth: true },
      children: [
        { path: "", redirect: { name: "home" } },
        {
          path: "home",
          name: "home",
          component: () => import("../views/home/HomeView.vue"),
        },
        {
          path: "chat",
          name: "chat",
          component: () => import("../views/chat/ChatView.vue"),
        },
        {
          path: "survey",
          name: "survey",
          component: () => import("../views/surveys/SurveyView.vue"),
        },
        {
          path: "courses",
          name: "courses",
          component: () => import("../views/courses/CoursesView.vue"),
        },
        {
          path: "reports",
          name: "reports",
          component: () => import("../views/reports/ReportsView.vue"),
        },
        {
          path: "posts",
          name: "posts",
          component: () => import("../views/posts/PostsView.vue"),
        },
        {
          path: "admin",
          name: "admin",
          meta: { adminOnly: true },
          component: () => import("../views/admin/AdminView.vue"),
        },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: { name: "home" } },
  ],
});
const auth = useAuth();
installAuthGuard(router, auth.restore, () => auth.identity.value);
