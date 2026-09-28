import { ref, type Ref } from "vue";
import {
  listKnowledge,
  addKnowledge as saveKnowledge,
} from "../../api/knowledge.js";
import type { Citation } from "../../types/knowledge.js";
import type { ActionRunner } from "../../types/common.js";
import type { KnowledgeForm } from "../../types/knowledge.js";
export function useKnowledge(
  run: ActionRunner,
  notice: Ref<string>,
  health: Ref<{ retrieval: string }>,
) {
  const knowledge = ref<Citation[]>([]);
  const importing = ref(false);
  const importForm = ref<KnowledgeForm>({
    title: "",
    topic: "情绪觉察",
    version: "v1",
    sourceUrl: "",
    text: "",
  });
  async function loadKnowledge() {
    knowledge.value = await listKnowledge();
  }
  async function addKnowledge() {
    if (importing.value) return;
    importing.value = true;
    try {
      await run(async () => {
        await saveKnowledge(importForm.value);
        await loadKnowledge();
        importForm.value = {
          ...importForm.value,
          title: "",
          text: "",
          sourceUrl: "",
        };
        notice.value =
          health.value.retrieval === "qdrant"
            ? "材料已保存，词项检索已可用；同步向量索引后启用语义检索"
            : "材料已保存，可立即检索";
      });
    } finally {
      importing.value = false;
    }
  }
  return { knowledge, importing, importForm, loadKnowledge, addKnowledge };
}
