export const displayDate = (value: string) =>
  new Date(value).toLocaleDateString("zh-CN", {
    month: "long",
    day: "numeric",
  });
