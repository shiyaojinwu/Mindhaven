<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { X } from "lucide-vue-next";
defineProps<{ error?: string }>();
const emit = defineEmits<{ close: [] }>();
const element = ref<HTMLElement | null>(null);
let previous: HTMLElement | null = null;
onMounted(async () => {
  previous = document.activeElement as HTMLElement | null;
  await nextTick();
  element.value?.querySelector<HTMLElement>(".modal-close")?.focus();
});
onBeforeUnmount(() => {
  void nextTick(() => {
    if (previous?.isConnected) previous.focus();
  });
});
function trapFocus(event: KeyboardEvent) {
  const nodes = Array.from(
    element.value?.querySelectorAll<HTMLElement>(
      "button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),summary",
    ) ?? [],
  ).filter((node) => node.getClientRects().length > 0);
  const first = nodes[0],
    last = nodes[nodes.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
</script>

<template>
  <div class="modal-overlay" @click.self="emit('close')">
    <section
      ref="element"
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-label="详细内容"
      tabindex="-1"
      @keydown.tab="trapFocus"
      @keydown.esc="emit('close')"
    >
      <button
        class="modal-close icon-button"
        aria-label="关闭详情"
        @click="emit('close')"
      >
        <X />
      </button>
      <p v-if="error" class="modal-error" role="alert">{{ error }}</p>
      <slot />
    </section>
  </div>
</template>
