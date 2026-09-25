<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import { Leaf } from "lucide-vue-next";
import Workspace from "./Workspace.vue";
import { api, ApiError, type Identity } from "./api";
const identity = ref<Identity | null>(null),
  ready = ref(false),
  register = ref(false),
  busy = ref(false),
  error = ref("");
const form = ref({
  tenantSlug: "",
  tenantName: "",
  username: "",
  password: "",
});
function expired() {
  identity.value = null;
  form.value.password = "";
  error.value = "登录已过期，请重新登录。";
}
onUnmounted(() =>
  window.removeEventListener("mindhaven:session-expired", expired),
);
onMounted(async () => {
  window.addEventListener("mindhaven:session-expired", expired);
  try {
    identity.value = await api<Identity>("/auth/me");
  } catch {
  } finally {
    ready.value = true;
  }
});
async function submit() {
  busy.value = true;
  error.value = "";
  try {
    identity.value = await api<Identity>(
      register.value ? "/auth/register" : "/auth/login",
      "POST",
      form.value,
    );
    form.value.password = "";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function logout() {
  try {
    try {
      await api("/auth/logout", "POST");
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 401) throw e;
    }
    identity.value = null;
    form.value.password = "";
  } catch (e) {
    error.value = (e as Error).message;
  }
}
</script>
<template>
  <p v-if="identity && error" class="alert error" role="alert">{{ error }}</p>
  <div v-if="!ready" class="auth-shell">正在打开你的心屿…</div>
  <Workspace
    v-else-if="identity"
    :identity="identity"
    :key="identity.userId"
    @logout="logout"
  />
  <div v-else class="auth-shell">
    <section class="auth-story">
      <span class="brand-mark"><Leaf :size="30" /></span>
      <p class="eyebrow">MINDHAVEN · 心屿</p>
      <h1>每一种感受，<br />都值得被倾听。</h1>
      <p>
        与学校、机构一起，建立一个温和的心理支持空间。<br />从一次记录、一段对话开始。
      </p>
      <div class="auth-note">
        机构共享问卷与微课堂<br />个人聊天与成长记录独立保存
      </div>
    </section>
    <form class="auth-card" @submit.prevent="submit">
      <span class="eyebrow">你的心灵栖息地</span>
      <h2>{{ register ? "创建机构空间" : "欢迎回到心屿" }}</h2>
      <p class="muted">
        {{
          register
            ? "创建后，你将成为这个机构的管理员。"
            : "使用管理员提供的机构代码和账号登录。"
        }}
      </p>
      <label
        >机构代码<input
          v-model.trim="form.tenantSlug"
          required
          pattern="[a-z0-9][a-z0-9-]{2,39}"
          maxlength="40"
          placeholder="例如：sunshine-school"
          autocomplete="organization" /></label
      ><small class="muted"
        >3–40 位小写字母、数字或连字符，首位不能是连字符</small
      >
      <label v-if="register"
        >机构名称<input
          v-model.trim="form.tenantName"
          required
          maxlength="120"
          placeholder="例如：向阳中学"
      /></label>
      <label
        >用户名<input
          v-model.trim="form.username"
          required
          pattern="[a-zA-Z0-9_.-]{3,40}"
          maxlength="40"
          autocomplete="username"
          placeholder="3–40 位英文、数字、下划线等"
      /></label>
      <label
        >密码<input
          v-model="form.password"
          type="password"
          required
          :minlength="register ? 10 : 1"
          maxlength="128"
          :autocomplete="register ? 'new-password' : 'current-password'"
          placeholder="至少 10 个字符"
      /></label>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p>
      <button class="primary-button" :disabled="busy">
        {{ busy ? "请稍候…" : register ? "创建并进入" : "登录空间" }}</button
      ><button
        type="button"
        class="text-button"
        @click="
          register = !register;
          error = '';
        "
      >
        {{ register ? "已有机构账号？去登录" : "我是管理员，创建新机构" }}
      </button>
      <p class="small muted">
        心屿提供记录与科普支持，不用于诊断，也不替代专业咨询。
      </p>
    </form>
  </div>
</template>
