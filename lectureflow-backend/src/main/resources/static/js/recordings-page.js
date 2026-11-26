import * as api from "./api.js";
import {
  checkAudioFile, clearMessage, el, formatDate, isProcessing, showMessage, startPolling, statusBadge,
} from "./ui.js";

const PAGE_SIZE = 10;
const POLL_INTERVAL_MS = 5000;

const state = {
  page: 0,
  sort: "createdAt",
  direction: "desc",
  items: [],
  stopPolling: null,
  dialogRecording: null,
};

const list = document.getElementById("recording-list");
const listMessage = document.getElementById("list-message");
const listLoading = document.getElementById("list-loading");
const listEmpty = document.getElementById("list-empty");
const pagination = document.getElementById("pagination");
const pageInfo = document.getElementById("page-info");
const prevButton = document.getElementById("prev-page");
const nextButton = document.getElementById("next-page");
const sortSelect = document.getElementById("sort-select");

async function loadPage() {
  listLoading.hidden = false;
  clearMessage(listMessage);
  try {
    const result = await api.getRecordingPage(state.page, PAGE_SIZE, state.sort, state.direction);

    if (result.items.length === 0 && state.page > 0) {
      state.page = state.page - 1;
      await loadPage();
      return;
    }
    render(result);
  } catch (error) {
    showMessage(listMessage, "Не удалось загрузить список: " + error.message, "error");
  } finally {
    listLoading.hidden = true;
  }
}

function render(result) {
  state.items = result.items;
  list.replaceChildren();
  for (const recording of result.items) {
    list.append(renderItem(recording));
  }
  listEmpty.hidden = result.items.length > 0;

  pagination.hidden = result.totalPages <= 1;
  pageInfo.textContent = "Страница " + (result.page + 1) + " из " + Math.max(result.totalPages, 1)
    + " · всего " + result.totalItems;
  prevButton.disabled = result.page === 0;
  nextButton.disabled = result.page + 1 >= result.totalPages;

  updatePolling();
}

function renderItem(recording) {
  const item = el("li", { className: "recording-item", dataset: { id: recording.id } });

  const main = el("div", { className: "recording-item__main" });
  const title = el("a", { className: "recording-item__title", href: "/recording.html?id=" + recording.id }, recording.title);
  const meta = el("div", { className: "recording-item__meta" });
  meta.append(el("span", null, formatDate(recording.createdAt)));
  const badgeHolder = el("span", { className: "status-holder" });
  badgeHolder.append(statusBadge(recording.status));
  meta.append(badgeHolder);
  main.append(title, meta);

  const actions = el("div", { className: "actions" });
  const renameButton = el("button", {
    className: "button button--secondary button--small", type: "button",
    "aria-label": "Переименовать запись «" + recording.title + "»",
  }, "Переименовать");
  renameButton.addEventListener("click", () => openRenameDialog(recording));
  const deleteButton = el("button", {
    className: "button button--secondary button--small", type: "button",
    "aria-label": "Удалить запись «" + recording.title + "»",
  }, "Удалить");
  deleteButton.addEventListener("click", () => openDeleteDialog(recording));
  actions.append(renameButton, deleteButton);

  item.append(main, actions);
  return item;
}

function updatePolling() {
  const needPolling = state.items.some((recording) => isProcessing(recording.status));
  if (needPolling && !state.stopPolling) {
    state.stopPolling = startPolling(refreshStatuses, POLL_INTERVAL_MS);
  }
  if (!needPolling && state.stopPolling) {
    state.stopPolling();
    state.stopPolling = null;
  }
}

async function refreshStatuses() {
  const result = await api.getRecordingPage(state.page, PAGE_SIZE, state.sort, state.direction);
  const sameItems = result.items.length === state.items.length
    && result.items.every((recording, index) => recording.id === state.items[index].id);
  if (sameItems) {

    for (const recording of result.items) {
      const holder = list.querySelector('li[data-id="' + recording.id + '"] .status-holder');
      if (holder) {
        holder.replaceChildren(statusBadge(recording.status));
      }
    }
    state.items = result.items;
  } else {
    render(result);
  }
  const stillProcessing = result.items.some((recording) => isProcessing(recording.status));
  if (!stillProcessing) {
    state.stopPolling = null;
  }
  return stillProcessing;
}

prevButton.addEventListener("click", () => {
  state.page = Math.max(0, state.page - 1);
  loadPage();
});

nextButton.addEventListener("click", () => {
  state.page = state.page + 1;
  loadPage();
});

sortSelect.addEventListener("change", () => {
  const [sort, direction] = sortSelect.value.split(",");
  state.sort = sort;
  state.direction = direction;
  state.page = 0;
  loadPage();
});

const createForm = document.getElementById("create-form");
const createTitle = document.getElementById("create-title-input");
const createFile = document.getElementById("create-file-input");
const createSubmit = document.getElementById("create-submit");
const createProgress = document.getElementById("create-progress");
const createProgressBar = document.getElementById("create-progress-bar");
const createProgressText = document.getElementById("create-progress-text");
const createMessage = document.getElementById("create-message");

function setCreateBusy(busy) {
  createSubmit.disabled = busy;
  createTitle.disabled = busy;
  createFile.disabled = busy;
  createProgress.hidden = !busy;
}

function setProgress(percent, text) {
  createProgressBar.value = percent;
  createProgressText.textContent = text;
}

createForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (createSubmit.disabled) {
    return;
  }
  clearMessage(createMessage);

  const title = createTitle.value.trim();
  const file = createFile.files[0];
  if (!title) {
    showMessage(createMessage, "Введите название записи.", "error");
    createTitle.focus();
    return;
  }
  const fileProblem = checkAudioFile(file);
  if (fileProblem) {
    showMessage(createMessage, fileProblem, "error");
    createFile.focus();
    return;
  }

  setCreateBusy(true);
  setProgress(0, "Создаём запись…");
  let recording = null;
  try {
    recording = await api.createRecording(title);
    setProgress(0, "Загружаем аудио…");
    await api.uploadAudio(recording.id, file, (percent) => {
      setProgress(percent, percent < 100 ? "Загружаем аудио: " + percent + "%" : "Сохраняем файл и ставим в очередь…");
    });
    window.location.href = "/recording.html?id=" + recording.id;
  } catch (error) {
    setCreateBusy(false);
    if (recording && error.code === "QUEUE_UNAVAILABLE") {

      window.location.href = "/recording.html?id=" + recording.id;
      return;
    }
    if (recording) {
      showMessage(createMessage, "Запись «" + recording.title + "» создана, но аудио не загружено: "
        + error.message + " Откройте запись, чтобы загрузить файл ещё раз.", "error");
      loadPage();
    } else {
      showMessage(createMessage, "Не удалось создать запись: " + error.message, "error");
    }
  }
});

const renameDialog = document.getElementById("rename-dialog");
const renameForm = document.getElementById("rename-form");
const renameInput = document.getElementById("rename-input");
const renameSubmit = document.getElementById("rename-submit");
const renameMessage = document.getElementById("rename-message");

function openRenameDialog(recording) {
  state.dialogRecording = recording;
  renameInput.value = recording.title;
  clearMessage(renameMessage);
  renameSubmit.disabled = false;
  renameDialog.showModal();
  renameInput.select();
}

document.getElementById("rename-cancel").addEventListener("click", () => renameDialog.close());

renameForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const title = renameInput.value.trim();
  if (!title) {
    showMessage(renameMessage, "Название не может быть пустым.", "error");
    return;
  }
  renameSubmit.disabled = true;
  try {
    await api.renameRecording(state.dialogRecording.id, title);
    renameDialog.close();
    loadPage();
  } catch (error) {
    showMessage(renameMessage, error.message, "error");
    renameSubmit.disabled = false;
  }
});

const deleteDialog = document.getElementById("delete-dialog");
const deleteText = document.getElementById("delete-dialog-text");
const deleteConfirm = document.getElementById("delete-confirm");
const deleteMessage = document.getElementById("delete-message");

function openDeleteDialog(recording) {
  state.dialogRecording = recording;
  deleteText.textContent = "Запись «" + recording.title + "» будет удалена вместе с аудио, транскрипцией "
    + "и конспектами. Отменить это действие нельзя.";
  clearMessage(deleteMessage);
  deleteConfirm.disabled = false;
  deleteDialog.showModal();
}

document.getElementById("delete-cancel").addEventListener("click", () => deleteDialog.close());

deleteConfirm.addEventListener("click", async () => {
  deleteConfirm.disabled = true;
  try {
    await api.deleteRecording(state.dialogRecording.id);
    deleteDialog.close();
    loadPage();
  } catch (error) {
    if (error.status === 404) {
      deleteDialog.close();
      loadPage();
      return;
    }
    showMessage(deleteMessage, error.message, "error");
    deleteConfirm.disabled = false;
  }
});

loadPage();
