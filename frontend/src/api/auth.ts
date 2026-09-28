import { api } from "./http.js";
import type { Identity, LoginForm } from "../types/auth.js";
export const getIdentity = () => api<Identity>("/auth/me");
export const login = (form: LoginForm, register: boolean) =>
  api<Identity>(register ? "/auth/register" : "/auth/login", "POST", form);
export const logout = () => api<void>("/auth/logout", "POST");
