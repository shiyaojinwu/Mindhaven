export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const res = await fetch("/api" + path, {
    method,
    signal: AbortSignal.timeout(120000),
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    if (res.status === 401 && !path.startsWith("/auth/"))
      window.dispatchEvent(new Event("mindhaven:session-expired"));
    const error = await res.json().catch(() => ({ message: "服务暂时不可用" }));
    throw new ApiError(res.status, error.message ?? "请求失败");
  }
  return res.status === 204 || method === "DELETE"
    ? (undefined as T)
    : res.json();
}
