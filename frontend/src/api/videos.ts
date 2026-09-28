/** XHR is retained for upload progress and explicit cancellation. */
export function uploadVideo(file: File, onProgress: (percent: number) => void) {
  const xhr = new XMLHttpRequest();
  const result = new Promise<{ id: string }>((resolve, reject) => {
    xhr.open("POST", "/api/admin/videos");
    xhr.timeout = 30 * 60 * 1000;
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable)
        onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = () => {
      let data;
      try {
        data = JSON.parse(xhr.responseText);
      } catch {
        reject(new Error("上传失败，请检查服务"));
        return;
      }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else {
        if (xhr.status === 401)
          window.dispatchEvent(new Event("mindhaven:session-expired"));
        reject(new Error(data.message || "上传失败"));
      }
    };
    xhr.onerror = () => reject(new Error("网络中断，请重新上传"));
    xhr.ontimeout = () => reject(new Error("上传超时，请重试"));
    xhr.onabort = () => reject(new Error("上传已取消"));
    const form = new FormData();
    form.append("file", file);
    xhr.send(form);
  });
  return { result, abort: () => xhr.abort() };
}
