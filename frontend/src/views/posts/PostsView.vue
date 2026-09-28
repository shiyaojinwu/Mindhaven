<script setup lang="ts">
import PostsPanel from "./PostsPanel.vue";
import { useWorkspace } from "../../layouts/workspaceContext.js";
const { posts, busy, postText, postMood, publishPost, hug, removePost } =
  useWorkspace();
</script>
<template>
  <PostsPanel
    :posts="posts"
    :busy="busy"
    v-model:text="postText"
    v-model:mood="postMood"
    @publish="publishPost"
    @hug="hug"
    @remove="removePost"
  />
</template>
