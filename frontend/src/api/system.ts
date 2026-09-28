import { api } from "./http.js";
export const getHealth = () =>
  api<{ mode: string; retrieval: string; retrievalStrategy: string }>(
    "/health",
  );
