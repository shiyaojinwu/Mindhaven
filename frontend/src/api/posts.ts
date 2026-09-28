import { api } from "./http.js";
import type { Post } from "../types/posts.js";
export const listPosts = () => api<Post[]>("/posts");
export const createPost = (body: { content: string; mood: string }) =>
  api<Post>("/posts", "POST", body);
export const hugPost = (id: string) => api<Post>(`/posts/${id}/hug`, "POST");
export const deletePost = (id: string) => api<void>(`/posts/${id}`, "DELETE");
