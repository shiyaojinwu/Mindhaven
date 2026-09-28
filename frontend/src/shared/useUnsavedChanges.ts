import {
  computed,
  inject,
  provide,
  ref,
  onBeforeUnmount,
  onMounted,
  type InjectionKey,
  type Ref,
} from "vue";
const navigationGuards: InjectionKey<Set<() => boolean>> =
  Symbol("navigationGuards");
const discardConfirmation: InjectionKey<() => Promise<boolean>> = Symbol(
  "discardConfirmation",
);
export const navigationCheck: InjectionKey<() => Promise<boolean>> =
  Symbol("navigationCheck");

export function provideNavigationGuard() {
  const guards = new Set<() => boolean>();
  const showDiscard = ref(false);
  let pending: Promise<boolean> | null = null;
  let resolve: ((answer: boolean) => void) | null = null;
  function confirm() {
    if (!pending)
      pending = new Promise<boolean>((answer) => {
        resolve = answer;
        showDiscard.value = true;
      });
    return pending;
  }
  function resolveDiscard(answer: boolean) {
    resolve?.(answer);
    resolve = null;
    pending = null;
    showDiscard.value = false;
  }
  async function canLeave() {
    return [...guards].every((guard) => guard()) || (await confirm());
  }
  provide(navigationGuards, guards);
  provide(discardConfirmation, confirm);
  provide(navigationCheck, canLeave);
  onBeforeUnmount(() => resolveDiscard(false));
  return { showDiscard, resolveDiscard, canLeave };
}

export function useUnsavedChanges(dirty: Ref<boolean>) {
  const guards = inject(navigationGuards, null);
  const confirm = inject(discardConfirmation, async () => false);
  const clean = () => !dirty.value;
  const ask = async () => clean() || (await confirm());
  const beforeUnload = (event: BeforeUnloadEvent) => {
    if (dirty.value) {
      event.preventDefault();
      event.returnValue = "";
    }
  };
  guards?.add(clean);
  onMounted(() => window.addEventListener("beforeunload", beforeUnload));
  onBeforeUnmount(() => {
    guards?.delete(clean);
    window.removeEventListener("beforeunload", beforeUnload);
  });
  return ask;
}
export const changed = <T>(value: Ref<T>, baseline: Ref<string>) =>
  computed(
    () => value.value != null && JSON.stringify(value.value) !== baseline.value,
  );
