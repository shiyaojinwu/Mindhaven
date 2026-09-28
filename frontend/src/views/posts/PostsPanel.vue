<script setup lang="ts">
import { Heart, Leaf, Trash2 } from "lucide-vue-next";
import type { Post } from "../../types/posts.js";
import { displayDate as date } from "../../utils/date.js";
defineProps<{ posts: Post[]; busy: boolean }>();
const postText = defineModel<string>("text", { required: true });
const postMood = defineModel<string>("mood", { required: true });
const emit = defineEmits<{
  publish: [];
  hug: [id: string];
  remove: [id: string];
}>();
</script>

<template>
  <div class="tree-layout">
    <section class="post-compose">
      <span class="feature-icon sage"><Heart :size="26" /></span>
      <h2>今天想留下些什么？</h2>
      <p>这里是本地私人树洞，内容不会发布到公共社区。</p>
      <label class="sr-only" for="post">心事内容</label
      ><textarea
        id="post"
        v-model="postText"
        maxlength="1000"
        placeholder="写下那些还没来得及说的话…"
      ></textarea>
      <div class="post-bottom">
        <label
          >心情<select v-model="postMood">
            <option>想说说</option>
            <option>小确幸</option>
            <option>有点累</option>
            <option>期待中</option>
          </select></label
        ><span>{{ postText.length }} / 1000</span>
      </div>
      <button
        class="primary-button"
        :disabled="!postText.trim() || busy"
        @click="emit('publish')"
      >
        放进树洞<Leaf :size="17" />
      </button>
    </section>
    <section class="posts-feed">
      <div class="section-header">
        <h2>我的心事小纸条</h2>
        <span>{{ posts.length }} 条记录</span>
      </div>
      <div v-if="!posts.length" class="empty-state">
        <Heart :size="30" />
        <p>还没有纸条。想到什么，就写一点吧。</p>
      </div>
      <article v-for="p in posts" :key="p.id" class="post-card">
        <div class="post-meta">
          <span class="tag">{{ p.mood }}</span
          ><time>{{ date(p.createdAt) }}</time>
        </div>
        <p>{{ p.content }}</p>
        <footer>
          <button
            class="text-button"
            :disabled="busy || p.hugs > 0"
            @click="emit('hug', p.id)"
          >
            <Heart :size="17" />{{
              p.hugs ? "已给自己一个拥抱" : "给自己一个拥抱"
            }}</button
          ><button
            class="icon-button"
            aria-label="删除这条心事"
            :disabled="busy"
            @click="emit('remove', p.id)"
          >
            <Trash2 :size="16" />
          </button>
        </footer>
      </article>
    </section>
  </div>
</template>
