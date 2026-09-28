import { ref, type Ref } from "vue";
import { listPosts, createPost, hugPost, deletePost } from "../../api/posts.js";
import type { Post } from "../../types/posts.js";
import type { ActionRunner } from "../../types/common.js";
export function usePosts(run: ActionRunner, notice: Ref<string>) {
  const posts = ref<Post[]>([]),
    postText = ref(""),
    postMood = ref("想说说");
  async function loadPosts() {
    posts.value = await listPosts();
  }
  async function publishPost() {
    if (!postText.value.trim()) return;
    await run(async () => {
      const p = await createPost({
        content: postText.value,
        mood: postMood.value,
      });
      posts.value.unshift(p);
      postText.value = "";
      notice.value = "你的心事已经保存";
    });
  }
  async function hug(id: string) {
    await run(async () => {
      const p = await hugPost(id);
      posts.value = posts.value.map((x) => (x.id === id ? p : x));
    });
  }
  async function removePost(id: string) {
    await run(async () => {
      await deletePost(id);
      posts.value = posts.value.filter((x) => x.id !== id);
    });
  }

  return { posts, postText, postMood, loadPosts, publishPost, hug, removePost };
}
