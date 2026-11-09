// Open GreetConsole as a normal resizable desktop-style extension window.
const APP_URL = chrome.runtime.getURL("popup.html");

chrome.action.onClicked.addListener(async () => {
  try {
    const windows = await chrome.windows.getAll({ populate: true });
    const existing = windows.find(win =>
      win.type === "popup" &&
      Array.isArray(win.tabs) &&
      win.tabs.some(tab => typeof tab.url === "string" && tab.url.startsWith(APP_URL))
    );

    if (existing && Number.isInteger(existing.id)) {
      await chrome.windows.update(existing.id, { focused: true });
      return;
    }

    await chrome.windows.create({
      url: APP_URL,
      type: "popup",
      width: 1160,
      height: 850,
      focused: true
    });
  } catch (error) {
    console.error("GreetConsole: не удалось открыть отдельное окно.", error);
  }
});
