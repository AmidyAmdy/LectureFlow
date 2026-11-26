export const MAX_UPLOAD_BYTES = 200 * 1024 * 1024;

const STATUS_LABELS = {
  NEW: "Нет аудио",
  UPLOADED: "Распознаётся",
  TRANSCRIBED: "Готовятся конспекты",
  SUMMARIZED: "Готово",
  FAILED: "Ошибка",
};

const STAGE_LABELS = {
  queue: "отправка задания в очередь",
  validate: "проверка задания",
  source: "проверка аудиофайла",
  lookup_transcription: "поиск готовой транскрипции",
  download: "скачивание аудио",
  transcribe: "распознавание речи",
  upload_transcription: "сохранение транскрипции",
  publish_transcription: "уведомление о транскрипции",
  lookup_summaries: "поиск готовых конспектов",
  summarize: "конспектирование",
  upload_summaries: "сохранение конспектов",
  publish_summary: "уведомление о конспектах",
  unknown: "неизвестный этап",
};

export function el(tag, attributes, text) {
  const element = document.createElement(tag);
  if (attributes) {
    for (const name of Object.keys(attributes)) {
      const value = attributes[name];
      if (name === "className") {
        element.className = value;
      } else if (name === "dataset") {
        Object.assign(element.dataset, value);
      } else {
        element.setAttribute(name, value);
      }
    }
  }
  if (text !== undefined && text !== null) {
    element.textContent = text;
  }
  return element;
}

export function showMessage(container, text, kind) {
  container.className = "message message--" + (kind || "info");
  container.textContent = text || "";
}

export function clearMessage(container) {
  container.textContent = "";
}

export function isProcessing(status) {
  return status === "UPLOADED" || status === "TRANSCRIBED";
}

export function statusBadge(status) {
  return el("span", { className: "badge badge--" + status }, STATUS_LABELS[status] || status);
}

export function stageLabel(stage) {
  return STAGE_LABELS[stage] || stage || "неизвестный этап";
}

export function formatTime(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  const twoDigits = (value) => String(value).padStart(2, "0");
  if (hours > 0) {
    return hours + ":" + twoDigits(minutes) + ":" + twoDigits(secs);
  }
  return minutes + ":" + twoDigits(secs);
}

export function formatDate(isoString) {
  const date = new Date(isoString);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return date.toLocaleString("ru-RU", { dateStyle: "medium", timeStyle: "short" });
}

export function checkAudioFile(file) {
  if (!file) {
    return "Выберите аудиофайл.";
  }
  if (file.size === 0) {
    return "Файл пустой. Выберите другой файл.";
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return "Файл больше 200 МБ. Сократите запись или увеличьте LECTUREFLOW_MAX_UPLOAD_SIZE.";
  }
  return null;
}

export function startPolling(task, intervalMs) {
  let timer = null;
  let running = false;
  let stopped = false;

  async function tick() {
    timer = null;
    if (stopped || document.hidden) {
      return;
    }
    running = true;
    let keepGoing = true;
    try {
      keepGoing = await task();
    } catch (error) {
      keepGoing = true;
    }
    running = false;
    if (keepGoing && !stopped) {
      timer = setTimeout(tick, intervalMs);
    } else {
      stop();
    }
  }

  function onVisibilityChange() {
    if (!document.hidden && !stopped && !running && timer === null) {
      tick();
    }
  }

  function stop() {
    stopped = true;
    clearTimeout(timer);
    timer = null;
    document.removeEventListener("visibilitychange", onVisibilityChange);
  }

  document.addEventListener("visibilitychange", onVisibilityChange);
  timer = setTimeout(tick, intervalMs);
  return stop;
}
