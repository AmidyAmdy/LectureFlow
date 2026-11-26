import * as api from "./api.js";
import {
  checkAudioFile, clearMessage, el, formatDate, formatTime, isProcessing, showMessage, stageLabel,
  startPolling, statusBadge,
} from "./ui.js";

const POLL_INTERVAL_MS = 4000;

const params = new URLSearchParams(window.location.search);
const recordingId = params.get("id");
const targetTime = params.has("t") ? Number(params.get("t")) : null;

const state = {
  recording: null,
  renderedKey: null,
  transcriptLoaded: false,
  transcriptText: "",
  summariesLoaded: false,
  segmentItems: [],
  currentSegment: null,
  stopPolling: null,
  busy: false,
};

const pageMessage = document.getElementById("page-message");
const pageLoading = document.getElementById("page-loading");
const view = document.getElementById("recording-view");
const titleElement = document.getElementById("recording-title");
const dateElement = document.getElementById("recording-date");
const statusElement = document.getElementById("recording-status");
const statusPanel = document.getElementById("status-panel");
const actionMessage = document.getElementById("action-message");
const failureActions = document.getElementById("failure-actions");
const retryButton = document.getElementById("retry-button");
const resendDetails = document.getElementById("resend-details");
const resendButton = document.getElementById("resend-button");
const uploadForm = document.getElementById("upload-form");
const uploadLabel = document.getElementById("upload-label");
const uploadFile = document.getElementById("upload-file");
const uploadSubmit = document.getElementById("upload-submit");
const uploadProgress = document.getElementById("upload-progress");
const uploadProgressBar = document.getElementById("upload-progress-bar");
const uploadProgressText = document.getElementById("upload-progress-text");
const replaceDetails = document.getElementById("replace-details");
const replaceSlot = document.getElementById("replace-slot");
const playerCard = document.getElementById("player-card");
const audio = document.getElementById("audio");
const summariesContent = document.getElementById("summaries-content");
const transcriptContent = document.getElementById("transcript-content");
const downloadTranscriptButton = document.getElementById("download-transcript");

async function init() {
  if (!/^\d+$/.test(recordingId || "")) {
    pageLoading.hidden = true;
    showMessage(pageMessage, "Не указан номер записи. Вернитесь к списку и выберите запись.", "error");
    return;
  }
  try {
    const recording = await api.getRecording(recordingId);
    pageLoading.hidden = true;
    view.hidden = false;
    render(recording);
    await loadResults();
    updatePolling();
  } catch (error) {
    pageLoading.hidden = true;
    if (error.status === 404) {
      showMessage(pageMessage, "Запись не найдена: возможно, её удалили.", "error");
    } else {
      showMessage(pageMessage, "Не удалось загрузить запись: " + error.message, "error");
    }
  }
}

function render(recording) {
  state.recording = recording;

  const key = [recording.title, recording.status, recording.failureStage, recording.failureMessage,
    recording.audioAvailable, recording.transcriptionReady, recording.summaryReady].join("|");
  if (key === state.renderedKey) {
    return;
  }
  state.renderedKey = key;

  document.title = recording.title + " — LectureFlow";
  titleElement.textContent = recording.title;
  dateElement.textContent = formatDate(recording.createdAt);
  statusElement.replaceChildren(statusBadge(recording.status));
  renderStatusPanel(recording);

  failureActions.hidden = recording.status !== "FAILED";
  resendDetails.hidden = !isProcessing(recording.status);
  renderUploadForm(recording);
  renderPlayer(recording);
}

function renderStatusPanel(recording) {
  statusPanel.replaceChildren();
  if (recording.status === "NEW") {
    statusPanel.append(el("p", null, "Аудио ещё не загружено. Выберите файл, чтобы начать обработку."));
  } else if (recording.status === "UPLOADED") {
    statusPanel.append(withSpinner("Аудио в очереди на распознавание. Это может занять несколько минут — страница обновится сама."));
  } else if (recording.status === "TRANSCRIBED") {
    statusPanel.append(withSpinner("Транскрипция готова. Готовим конспекты частей…"));
  } else if (recording.status === "SUMMARIZED") {
    statusPanel.append(el("p", null, "Обработка завершена: транскрипция и конспекты частей готовы."));
  } else if (recording.status === "FAILED") {
    statusPanel.append(el("p", null, "Обработка остановилась на этапе «" + stageLabel(recording.failureStage) + "»."));
    if (recording.failureMessage) {
      statusPanel.append(el("p", { className: "failure-text small muted" }, recording.failureMessage));
    }
    if (recording.transcriptionReady) {
      statusPanel.append(el("p", null, "Готовая транскрипция доступна ниже."));
    }
  }
}

function withSpinner(text) {
  const paragraph = el("p");
  paragraph.append(el("span", { className: "spinner", "aria-hidden": "true" }), " " + text);
  return paragraph;
}

function renderUploadForm(recording) {
  uploadForm.hidden = false;
  if (recording.audioAvailable) {
    replaceSlot.append(uploadForm);
    replaceDetails.hidden = false;
    uploadLabel.textContent = "Новый аудиофайл";
    uploadSubmit.textContent = "Заменить аудио";
  } else {
    replaceDetails.after(uploadForm);
    replaceDetails.hidden = true;
    uploadLabel.textContent = "Аудиофайл";
    uploadSubmit.textContent = "Загрузить аудио";
  }
}

function renderPlayer(recording) {
  playerCard.hidden = !recording.audioAvailable;
  if (recording.audioAvailable && !audio.getAttribute("src")) {
    audio.src = api.audioUrl(recording.id);
    if (targetTime !== null && Number.isFinite(targetTime)) {

      audio.currentTime = targetTime;
    }
  }
}

async function loadResults() {
  const recording = state.recording;
  if (recording.transcriptionReady && !state.transcriptLoaded) {
    await loadTranscript();
  } else if (!recording.transcriptionReady) {
    state.transcriptLoaded = false;
    state.transcriptText = "";
    downloadTranscriptButton.disabled = true;
    transcriptContent.replaceChildren(placeholder(transcriptPlaceholderText(recording), isProcessing(recording.status)));
  }
  if (recording.summaryReady && !state.summariesLoaded) {
    await loadSummaries();
  } else if (!recording.summaryReady) {
    summariesContent.replaceChildren(placeholder(summaryPlaceholderText(recording), recording.status === "TRANSCRIBED"));
  }
}

function transcriptPlaceholderText(recording) {
  if (recording.status === "NEW") {
    return "Транскрипция появится после загрузки аудио.";
  }
  if (recording.status === "FAILED") {
    return "Транскрипция не получена из-за ошибки обработки.";
  }
  return "Распознаём речь…";
}

function summaryPlaceholderText(recording) {
  if (recording.status === "NEW") {
    return "Конспекты появятся после загрузки аудио.";
  }
  if (recording.status === "FAILED") {
    return "Конспекты не получены из-за ошибки обработки.";
  }
  if (recording.status === "TRANSCRIBED") {
    return "Готовим конспекты частей…";
  }
  return "Конспекты появятся после транскрипции.";
}

function placeholder(text, withSpinnerIcon) {
  if (withSpinnerIcon) {
    const result = withSpinner(text);
    result.className = "muted";
    return result;
  }
  return el("p", { className: "muted" }, text);
}

async function loadTranscript() {
  try {
    const segments = await api.getTranscription(recordingId);
    state.transcriptText = segments
      .map(segment => segment.text.trim())
      .filter(text => text.length > 0)
      .join("\n");
    downloadTranscriptButton.disabled = !state.transcriptText;
    renderTranscript(segments);
    state.transcriptLoaded = true;
    showTargetSegment();
  } catch (error) {
    transcriptContent.replaceChildren(loadError("Не удалось загрузить транскрипцию: " + error.message, loadTranscript));
  }
}

async function loadSummaries() {
  try {
    const parts = await api.getSummaries(recordingId);
    renderSummaries(parts);
    state.summariesLoaded = true;
  } catch (error) {
    summariesContent.replaceChildren(loadError("Не удалось загрузить конспекты: " + error.message, loadSummaries));
  }
}

function loadError(text, retry) {
  const box = el("div");
  box.append(el("p", { className: "message message--error" }, text));
  const button = el("button", { className: "button button--secondary button--small", type: "button" }, "Попробовать ещё раз");
  button.addEventListener("click", retry);
  box.append(button);
  return box;
}

function timecodeButton(start, label) {
  const button = el("button", {
    className: "timecode", type: "button", "aria-label": "Воспроизвести с " + formatTime(start),
  }, label);
  button.addEventListener("click", () => seek(start));
  return button;
}

function renderTranscript(segments) {
  state.segmentItems = [];
  state.currentSegment = null;
  if (segments.length === 0) {
    transcriptContent.replaceChildren(el("p", { className: "muted" }, "Транскрипция пустая."));
    return;
  }
  const listElement = el("ol", { className: "segment-list" });
  for (const segment of segments) {
    const item = el("li", { className: "segment" });
    item.append(timecodeButton(segment.start, formatTime(segment.start)));
    item.append(el("span", { className: "segment__text" }, segment.text.trim()));
    listElement.append(item);
    state.segmentItems.push({ start: segment.start, end: segment.end, element: item });
  }
  transcriptContent.replaceChildren(listElement);
}

function renderSummaries(parts) {
  const box = el("div");
  box.append(el("p", { className: "small muted" },
    "Конспекты отдельных частей лекции. Нажмите на время, чтобы прослушать эту часть."));
  if (parts.length === 0) {
    box.append(el("p", { className: "muted" }, "Конспектов нет."));
  }
  for (const part of parts) {
    const article = el("article", { className: "summary-part" });
    article.append(timecodeButton(part.start, formatTime(part.start) + " – " + formatTime(part.end)));
    article.append(el("p", { className: "summary-part__text" }, part.text.trim()));
    box.append(article);
  }
  summariesContent.replaceChildren(box);
}

function seek(seconds) {
  if (playerCard.hidden) {
    return;
  }
  audio.currentTime = seconds;
  audio.play().catch(() => {

  });
}

function findSegmentAt(seconds) {
  for (const item of state.segmentItems) {
    if (seconds >= item.start && seconds < item.end) {
      return item;
    }
  }
  return null;
}

audio.addEventListener("timeupdate", () => {
  const item = findSegmentAt(audio.currentTime);
  if (item === state.currentSegment) {
    return;
  }
  if (state.currentSegment) {
    state.currentSegment.element.classList.remove("is-current");
  }
  if (item) {
    item.element.classList.add("is-current");
  }
  state.currentSegment = item;
});

function showTargetSegment() {
  if (targetTime === null || !Number.isFinite(targetTime)) {
    return;
  }
  let target = findSegmentAt(targetTime);
  if (!target) {
    target = state.segmentItems.find((item) => item.start >= targetTime) || null;
  }
  if (target) {
    target.element.classList.add("is-target");
    target.element.scrollIntoView({ block: "center" });
  }
}

function updatePolling() {
  const needPolling = isProcessing(state.recording.status);
  if (needPolling && !state.stopPolling) {
    state.stopPolling = startPolling(refresh, POLL_INTERVAL_MS);
  }
  if (!needPolling && state.stopPolling) {
    state.stopPolling();
    state.stopPolling = null;
  }
}

async function refresh() {
  const recording = await api.getRecording(recordingId);
  render(recording);
  await loadResults();
  const stillProcessing = isProcessing(recording.status);
  if (!stillProcessing) {
    state.stopPolling = null;
  }
  return stillProcessing;
}

downloadTranscriptButton.addEventListener("click", () => {
  if (!state.transcriptText) {
    return;
  }
  const blob = new Blob([state.transcriptText], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "transcription-" + recordingId + ".txt";
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});

async function restart(button) {
  if (state.busy) {
    return;
  }
  state.busy = true;
  button.disabled = true;
  clearMessage(actionMessage);
  try {
    const recording = await api.retryProcessing(recordingId);
    render(recording);
    await loadResults();
    showMessage(actionMessage, "Задание отправлено worker.", "success");
  } catch (error) {
    showMessage(actionMessage, error.message, "error");
    try {
      render(await api.getRecording(recordingId));
    } catch {}
  } finally {
    state.busy = false;
    button.disabled = false;
    updatePolling();
  }
}

retryButton.addEventListener("click", () => restart(retryButton));
resendButton.addEventListener("click", () => restart(resendButton));

uploadForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (state.busy) {
    return;
  }
  clearMessage(actionMessage);
  const file = uploadFile.files[0];
  const problem = checkAudioFile(file);
  if (problem) {
    showMessage(actionMessage, problem, "error");
    return;
  }
  if (state.recording.audioAvailable
      && !window.confirm("Заменить аудио? Текущие транскрипция и конспекты будут удалены.")) {
    return;
  }

  state.busy = true;
  uploadSubmit.disabled = true;
  uploadFile.disabled = true;
  uploadProgress.hidden = false;
  try {
    await api.uploadAudio(recordingId, file, (percent) => {
      uploadProgressBar.value = percent;
      uploadProgressText.textContent = percent < 100 ? "Загружаем: " + percent + "%" : "Сохраняем файл…";
    });
    window.location.replace("/recording.html?id=" + recordingId);
  } catch (error) {
    if (error.code === "QUEUE_UNAVAILABLE") {

      window.location.replace("/recording.html?id=" + recordingId);
      return;
    }
    showMessage(actionMessage, "Не удалось загрузить аудио: " + error.message, "error");
    state.busy = false;
    uploadSubmit.disabled = false;
    uploadFile.disabled = false;
    uploadProgress.hidden = true;
  }
});

init();
