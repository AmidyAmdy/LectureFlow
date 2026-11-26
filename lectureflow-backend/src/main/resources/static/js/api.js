export class ApiError extends Error {
  constructor(status, code, message, details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details || [];
  }
}

const NETWORK_ERROR_MESSAGE = "Сервер недоступен. Проверьте, что backend запущен.";

async function request(path, options) {
  let response;
  try {
    response = await fetch(path, options);
  } catch (error) {
    throw new ApiError(0, "NETWORK_ERROR", NETWORK_ERROR_MESSAGE, []);
  }
  if (!response.ok) {
    throw await toApiError(response.status, () => response.json());
  }
  if (response.status === 204) {
    return null;
  }
  return response.json();
}

async function toApiError(status, readJson) {
  let body = null;
  try {
    body = await readJson();
  } catch {}
  if (body && body.message) {
    return new ApiError(status, body.code, body.message, body.details);
  }
  return new ApiError(status, "HTTP_" + status, "Ошибка сервера (код " + status + ")", []);
}

function sendJson(method, path, data) {
  return request(path, {
    method: method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
}

export function getRecordingPage(page, size, sort, direction) {
  const params = new URLSearchParams({ page, size, sort, direction });
  return request("/recordings/page?" + params);
}

export function getRecording(id) {
  return request("/recordings/" + encodeURIComponent(id));
}

export function createRecording(title) {
  return sendJson("POST", "/recordings", { title: title });
}

export function renameRecording(id, title) {
  return sendJson("PATCH", "/recordings/" + encodeURIComponent(id), { title: title });
}

export function deleteRecording(id) {
  return request("/recordings/" + encodeURIComponent(id), { method: "DELETE" });
}

export function retryProcessing(id) {
  return request("/recordings/" + encodeURIComponent(id) + "/retry", { method: "POST" });
}

export function getTranscription(id) {
  return request("/recordings/" + encodeURIComponent(id) + "/transcription");
}

export function getSummaries(id) {
  return request("/recordings/" + encodeURIComponent(id) + "/summary");
}

export function searchSegments(query, page, size) {
  const params = new URLSearchParams({ q: query, page, size });
  return request("/search/segments?" + params);
}

export function audioUrl(id) {
  return "/recordings/" + encodeURIComponent(id) + "/audio";
}

export function uploadAudio(id, file, onProgress) {
  return new Promise(function (resolve, reject) {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/recordings/" + encodeURIComponent(id) + "/audio");
    xhr.upload.addEventListener("progress", function (event) {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    });
    xhr.addEventListener("load", async function () {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
        return;
      }
      reject(await toApiError(xhr.status, () => JSON.parse(xhr.responseText)));
    });
    xhr.addEventListener("error", function () {
      reject(new ApiError(0, "NETWORK_ERROR", NETWORK_ERROR_MESSAGE, []));
    });
    const formData = new FormData();
    formData.append("file", file);
    xhr.send(formData);
  });
}
