// Open GreetConsole in one resizable standalone window.
const APP_URL = chrome.runtime.getURL("popup.html");
const WINDOW_KEY = "greetConsoleWindowId";

chrome.action.onClicked.addListener(async () => {
  try {
    const stored = await chrome.storage.session.get(WINDOW_KEY);
    const windowId = stored[WINDOW_KEY];

    if (Number.isInteger(windowId)) {
      try {
        await chrome.windows.update(windowId, { focused: true });
        return;
      } catch {
        await chrome.storage.session.remove(WINDOW_KEY);
      }
    }

    const created = await chrome.windows.create({
      url: APP_URL,
      type: "popup",
      width: 1160,
      height: 850,
      focused: true
    });

    if (created && Number.isInteger(created.id)) {
      await chrome.storage.session.set({ [WINDOW_KEY]: created.id });
    }
  } catch (error) {
    console.error("GreetConsole: не удалось открыть отдельное окно.", error);
  }
});

chrome.windows.onRemoved.addListener(async removedId => {
  try {
    const stored = await chrome.storage.session.get(WINDOW_KEY);
    if (stored[WINDOW_KEY] === removedId) {
      await chrome.storage.session.remove(WINDOW_KEY);
    }
  } catch (error) {
    console.warn("GreetConsole: не удалось очистить ID закрытого окна.", error);
  }
});
