const API = "http://127.0.0.1:38761/api";
const els = Object.fromEntries([
  "status-dot","status-title","status-subtitle","pause-button","clear-button","copy-button",
  "search","level-filter","autoscroll","line-count","log-view","command-form","command-input",
  "send-button","command-feedback","player-info"
].map(id => [id, document.getElementById(id)]));

let token = null;
let cursor = 0;
let playerReady = false;
let entries = [];
let paused = false;
let scrollWasAtBottom = true;
let commandHistory = [];
let historyIndex = 0;
let reconnectTimer = null;

const minecraftColors = [
  "#000000","#0000AA","#00AA00","#00AAAA","#AA0000","#AA00AA","#FFAA00","#AAAAAA",
  "#555555","#5555FF","#55FF55","#55FFFF","#FF5555","#FF55FF","#FFFF55","#FFFFFF"
];
const colorCodes = "0123456789abcdef";

function apiFetch(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (token) headers.set("X-GreetConsole-Token", token);
  if (options.body) headers.set("Content-Type", "application/json");
  return fetch(API + path, {
    ...options, headers, mode: "cors", cache: "no-store", credentials: "omit"
  }).then(async response => {
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || `HTTP ${response.status}`);
    return data;
  });
}

async function connect() {
  try {
    const bootstrapResponse = await fetch(API + "/bootstrap", {
      mode: "cors", cache: "no-store", credentials: "omit"
    });
    if (!bootstrapResponse.ok) {
      const errorData = await bootstrapResponse.json().catch(() => ({}));
      throw new Error(errorData.message || `HTTP ${bootstrapResponse.status}: локальный мост не отвечает`);
    }
    const bootstrap = await bootstrapResponse.json();
    token = bootstrap.token;
    cursor = 0;
    await refreshStatus();
    await refreshLogs();
    setConnection(true, "Minecraft подключён", "Локальный мост активен");
    scheduleReconnect();
  } catch (error) {
    token = null;
    const reason = error && error.message ? error.message : String(error);
    setConnection(false, "Нет связи с модом", reason.slice(0, 90));
    setFeedback("Не удалось подключиться к локальному мосту: " + reason, false);
    scheduleReconnect();
  }
}

function scheduleReconnect() {
  if (reconnectTimer) clearTimeout(reconnectTimer);
  reconnectTimer = setTimeout(async () => {
    if (!token) await connect();
    else await poll();
  }, 1500);
}

async function refreshStatus() {
  const status = await apiFetch("/status");
  playerReady = !!status.connected;
  if (playerReady) {
    setConnection(true, status.player || "Игрок подключён", status.server || "Мир загружен");
    els["player-info"].textContent = `Игрок: ${status.player || "—"}  •  ${status.server || "Minecraft"}`;
    els["command-input"].disabled = false;
    els["send-button"].disabled = false;
  } else {
    setConnection(true, "Minecraft запущен", "Ожидание входа в мир");
    els["player-info"].textContent = "Игрок не подключён";
    els["command-input"].disabled = true;
    els["send-button"].disabled = true;
  }
}

async function refreshLogs() {
  if (paused) return;
  const data = await apiFetch(`/logs?since=${cursor}`);
  for (const item of data.lines || []) {
    entries.push(item);
    cursor = Math.max(cursor, Number(item.id) || 0);
  }
  if (entries.length > 3000) entries.splice(0, entries.length - 3000);
  renderLogs();
}

async function poll() {
  if (!token) return connect();
  try {
    await refreshStatus();
    await refreshLogs();
    scheduleReconnect();
  } catch (error) {
    token = null;
    const reason = error && error.message ? error.message : String(error);
    setConnection(false, "Соединение потеряно", reason.slice(0, 90));
    setFeedback("Связь с модом потеряна: " + reason, false);
    els["command-input"].disabled = true;
    els["send-button"].disabled = true;
    scheduleReconnect();
  }
}

function setConnection(online, title, subtitle) {
  els["status-dot"].className = online ? "dot online" : "dot offline";
  els["status-title"].textContent = title;
  els["status-subtitle"].textContent = subtitle;
}

function setFeedback(message, success) {
  els["command-feedback"].textContent = message;
  els["command-feedback"].style.color = success ? "#8ed7b4" : "#ff9b9f";
}

function getLevel(text) {
  const match = text.match(/\[(?:[^\]]*\/)?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\]/i);
  return match ? match[1].toUpperCase() : "OTHER";
}

function splitFormatted(text) {
  const segments = [];
  let style = { color: null, bold: false, italic: false, underline: false, strike: false };
  let buffer = "";
  const flush = () => {
    if (buffer) { segments.push({ text: buffer, style: { ...style } }); buffer = ""; }
  };

  for (let i = 0; i < text.length;) {
    if (text[i] === "\x1b" && text[i + 1] === "[") {
      const end = text.indexOf("m", i + 2);
      if (end >= 0) {
        const raw = text.slice(i + 2, end);
        const codes = raw.split(";").map(v => Number.parseInt(v, 10) || 0);
        flush();
        applyAnsi(codes, style);
        i = end + 1;
        continue;
      }
    }
    if ((text[i] === "&" || text[i] === "§") && i + 1 < text.length) {
      const next = text[i + 1].toLowerCase();
      if (next === "#" && /^[0-9a-f]{6}$/i.test(text.slice(i + 2, i + 8))) {
        flush(); style.color = "#" + text.slice(i + 2, i + 8); i += 8; continue;
      }
      if (next === "x") {
        let pos = i + 2, hex = "";
        for (let n = 0; n < 6; n++) {
          if ((text[pos] !== "&" && text[pos] !== "§") || !/[0-9a-f]/i.test(text[pos + 1] || "")) break;
          hex += text[pos + 1]; pos += 2;
        }
        if (hex.length === 6) { flush(); style.color = "#" + hex; i = pos; continue; }
      }
      if (colorCodes.includes(next)) {
        flush(); style.color = minecraftColors[colorCodes.indexOf(next)];
        style.bold = style.italic = style.underline = style.strike = false;
        i += 2; continue;
      }
      if ("klmnor".includes(next)) {
        flush();
        if (next === "l") style.bold = true;
        if (next === "o") style.italic = true;
        if (next === "n") style.underline = true;
        if (next === "m") style.strike = true;
        if (next === "r") style = { color: null, bold: false, italic: false, underline: false, strike: false };
        i += 2; continue;
      }
    }
    buffer += text[i++];
  }
  flush();
  return segments;
}

function applyAnsi(codes, style) {
  const clamp = v => Math.max(0, Math.min(255, v));
  const ansi256 = index => {
    index = clamp(index);
    if (index < 16) return minecraftColors[index];
    if (index >= 232) { const g = 8 + (index - 232) * 10; return `rgb(${g},${g},${g})`; }
    const levels = [0,95,135,175,215,255], n = index - 16;
    return `rgb(${levels[Math.floor(n / 36)]},${levels[Math.floor(n / 6) % 6]},${levels[n % 6]})`;
  };
  for (let i = 0; i < codes.length; i++) {
    const c = codes[i];
    if (c === 0) Object.assign(style, { color: null, bold: false, italic: false, underline: false, strike: false });
    else if (c === 1) style.bold = true;
    else if (c === 3) style.italic = true;
    else if (c === 4) style.underline = true;
    else if (c === 9) style.strike = true;
    else if (c === 22) style.bold = false;
    else if (c === 23) style.italic = false;
    else if (c === 24) style.underline = false;
    else if (c === 29) style.strike = false;
    else if (c >= 30 && c <= 37) style.color = minecraftColors[c - 30];
    else if (c >= 90 && c <= 97) style.color = minecraftColors[c - 90 + 8];
    else if (c === 39) style.color = null;
    else if (c === 38 && codes[i + 1] === 5 && codes[i + 2] !== undefined) { style.color = ansi256(codes[i + 2]); i += 2; }
    else if (c === 38 && codes[i + 1] === 2 && codes[i + 4] !== undefined) {
      style.color = `rgb(${clamp(codes[i + 2])},${clamp(codes[i + 3])},${clamp(codes[i + 4])})`; i += 4;
    }
  }
}

function appendStyled(parent, text, query) {
  const segments = splitFormatted(text);
  for (const segment of segments) {
    const parts = query ? splitForHighlight(segment.text, query) : [{ text: segment.text, marked: false }];
    for (const part of parts) {
      const span = document.createElement(part.marked ? "mark" : "span");
      span.textContent = part.text;
      const s = segment.style;
      if (s.color) span.style.color = s.color;
      if (s.bold) span.style.fontWeight = "700";
      if (s.italic) span.style.fontStyle = "italic";
      const decorations = [];
      if (s.underline) decorations.push("underline");
      if (s.strike) decorations.push("line-through");
      if (decorations.length) span.style.textDecoration = decorations.join(" ");
      parent.appendChild(span);
    }
  }
}

function splitForHighlight(text, query) {
  const out = [];
  const lower = text.toLowerCase(), needle = query.toLowerCase();
  let at = 0, found;
  while ((found = lower.indexOf(needle, at)) !== -1) {
    if (found > at) out.push({ text: text.slice(at, found), marked: false });
    out.push({ text: text.slice(found, found + needle.length), marked: true });
    at = found + needle.length;
  }
  if (at < text.length) out.push({ text: text.slice(at), marked: false });
  return out;
}

function renderLogs() {
  const query = els.search.value.trim().toLowerCase();
  const level = els["level-filter"].value;
  const view = els["log-view"];
  const atBottom = view.scrollHeight - view.scrollTop - view.clientHeight < 30;
  view.replaceChildren();
  let visible = 0;

  for (const entry of entries) {
    const text = entry.text || "";
    const actualLevel = getLevel(text);
    if (level !== "ALL" && actualLevel !== level) continue;
    if (query && !text.toLowerCase().includes(query)) continue;
    const row = document.createElement("div");
    row.className = `log-line level-${actualLevel}`;
    row.dataset.id = entry.id;
    appendStyled(row, text, query);
    view.appendChild(row);
    visible++;
  }

  if (visible === 0) {
    const empty = document.createElement("div");
    empty.className = "welcome";
    empty.textContent = entries.length ? "Нет строк по текущему фильтру." : "Ожидание новых записей журнала…";
    view.appendChild(empty);
  }
  els["line-count"].textContent = `${visible.toLocaleString("ru-RU")} строк`;
  if (els.autoscroll.checked && (atBottom || scrollWasAtBottom)) view.scrollTop = view.scrollHeight;
  scrollWasAtBottom = atBottom;
}

els["pause-button"].addEventListener("click", () => {
  paused = !paused;
  els["pause-button"].textContent = paused ? "Продолжить" : "Пауза";
  if (paused) {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    setFeedback("Журнал на паузе. Отправка сообщений и команд доступна.", true);
  } else {
    renderLogs();
    scheduleReconnect();
  }
});

els["clear-button"].addEventListener("click", () => {
  entries = [];
  renderLogs();
});

els["copy-button"].addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(entries.map(e => e.text).join("\n"));
    setFeedback("Журнал скопирован.", true);
  } catch {
    setFeedback("Браузер не разрешил копирование.", false);
  }
});

els.search.addEventListener("input", renderLogs);
els["level-filter"].addEventListener("change", renderLogs);
els.autoscroll.addEventListener("change", () => { if (els.autoscroll.checked) els["log-view"].scrollTop = els["log-view"].scrollHeight; });
els["log-view"].addEventListener("scroll", () => {
  scrollWasAtBottom = els["log-view"].scrollHeight - els["log-view"].scrollTop - els["log-view"].clientHeight < 30;
});

els["command-form"].addEventListener("submit", async event => {
  event.preventDefault();
  const command = els["command-input"].value.trim();
  if (!command) { setFeedback("Введи сообщение или команду.", false); return; }
  if (!token) { setFeedback("Minecraft не подключён.", false); return; }

  els["send-button"].disabled = true;
  try {
    const result = await apiFetch("/command", {
      method: "POST",
      body: JSON.stringify({ command })
    });
    if (result.ok) {
      commandHistory.unshift(command);
      commandHistory = [...new Set(commandHistory)].slice(0, 50);
      historyIndex = 0;
      els["command-input"].value = "";
      setFeedback(result.message || "Текст передан Minecraft.", true);
    } else {
      setFeedback(result.message || "Текст не отправлен.", false);
    }
  } catch (error) {
    setFeedback(error.message || "Ошибка отправки команды.", false);
  } finally {
    els["send-button"].disabled = !playerReady;
    els["command-input"].focus();
  }
});

els["command-input"].addEventListener("keydown", event => {
  if (event.key === "ArrowUp") {
    if (!commandHistory.length) return;
    event.preventDefault();
    historyIndex = Math.min(commandHistory.length, historyIndex + 1);
    els["command-input"].value = commandHistory[historyIndex - 1] || "";
  } else if (event.key === "ArrowDown") {
    if (!commandHistory.length) return;
    event.preventDefault();
    historyIndex = Math.max(0, historyIndex - 1);
    els["command-input"].value = historyIndex === 0 ? "" : commandHistory[historyIndex - 1];
  }
});

connect();
