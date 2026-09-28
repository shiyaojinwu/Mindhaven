import { ref } from "vue";
import * as authApi from "../api/auth.js";
import { ApiError } from "../api/http.js";
import type { Identity, LoginForm } from "../types/auth.js";
const identity = ref<Identity | null>(null);
const error = ref("");
let restored = false;
let restoring: Promise<void> | null = null;
async function restore() {
  if (restored) return;
  if (!restoring)
    restoring = (async () => {
      try {
        identity.value = await authApi.getIdentity();
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 401)
          error.value = "无法连接服务，请稍后重试登录。";
      } finally {
        restored = true;
        restoring = null;
      }
    })();
  await restoring;
}
async function login(form: LoginForm, register: boolean) {
  identity.value = await authApi.login(form, register);
  restored = true;
  error.value = "";
}
async function logout() {
  try {
    await authApi.logout();
  } catch (e) {
    if (!(e instanceof ApiError) || e.status !== 401) throw e;
  }
  identity.value = null;
  window.sessionStorage.removeItem("mindhaven:session");
}
function expire() {
  identity.value = null;
  error.value = "登录已过期，请重新登录。";
  window.sessionStorage.removeItem("mindhaven:session");
}
export function useAuth() {
  return { identity, error, restore, login, logout, expire };
}
