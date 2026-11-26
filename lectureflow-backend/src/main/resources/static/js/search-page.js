import * as api from "./api.js";
import { el, formatTime, showMessage } from "./ui.js";

const PAGE_SIZE = 20;

const params = new URLSearchParams(window.location.search);
const query = (params.get("q") || "").trim();
const page = Math.max(0, Number.parseInt(params.get("page") || "0", 10) || 0);

const input = document.getElementById("search-input");
const message = document.getElementById("search-message");
const status = document.getElementById("search-status");
const results = document.getElementById("search-results");
const pagination = document.getElementById("search-pagination");
const prevLink = document.getElementById("search-prev");
const nextLink = document.getElementById("search-next");
const pageInfo = document.getElementById("search-page-info");

function pageUrl(pageNumber) {
  return "/search.html?" + new URLSearchParams({ q: query, page: pageNumber });
}

function renderResult(result) {
  const item = el("li", { className: "search-result" });
  const head = el("div", { className: "search-result__head" });
  const recordingUrl = "/recording.html?id=" + result.recordingId;
  head.append(el("a", { className: "search-result__title", href: recordingUrl }, result.title));

  head.append(el("a", {
    className: "timecode",
    href: recordingUrl + "&t=" + result.start,
    "aria-label": "Слушать «" + result.title + "» с " + formatTime(result.start),
  }, "▶ " + formatTime(result.start) + " – " + formatTime(result.end)));
  item.append(head);
  item.append(el("p", { className: "search-result__text" }, result.text.trim()));
  return item;
}

async function search() {
  input.value = query;
  if (!query) {
    input.focus();
    return;
  }
  status.textContent = "Ищем…";
  try {
    const result = await api.searchSegments(query, page, PAGE_SIZE);
    results.replaceChildren();
    for (const item of result.items) {
      results.append(renderResult(item));
    }
    if (result.totalItems === 0) {
      status.textContent = "По запросу «" + query + "» ничего не найдено. Попробуйте другие слова.";
    } else {
      status.textContent = "Найдено фрагментов: " + result.totalItems + ".";
    }

    pagination.hidden = result.totalPages <= 1;
    pageInfo.textContent = "Страница " + (page + 1) + " из " + Math.max(result.totalPages, 1);
    prevLink.hidden = page === 0;
    prevLink.href = pageUrl(page - 1);
    nextLink.hidden = page + 1 >= result.totalPages;
    nextLink.href = pageUrl(page + 1);
  } catch (error) {
    status.textContent = "";
    showMessage(message, "Поиск не выполнен: " + error.message, "error");
  }
}

search();
