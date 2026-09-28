<script setup lang="ts">
import { onMounted, onUnmounted } from "vue";
import { RouterView, useRouter } from "vue-router";
import { useAuth } from "./composables/useAuth.js";
const auth = useAuth();
const { identity } = auth;
const router = useRouter();
function expired() {
  const redirect = router.currentRoute.value.fullPath;
  auth.expire();
  if (router.currentRoute.value.name !== "login")
    void router.replace({ name: "login", query: { redirect } });
}
onMounted(() => window.addEventListener("mindhaven:session-expired", expired));
onUnmounted(() =>
  window.removeEventListener("mindhaven:session-expired", expired),
);
</script>
<template>
  <RouterView v-slot="{ Component, route }">
    <component
      :is="Component"
      v-if="!route.meta.requiresAuth || identity"
      :key="identity ? identity.tenantId + ':' + identity.userId : 'guest'"
    />
  </RouterView>
</template>
