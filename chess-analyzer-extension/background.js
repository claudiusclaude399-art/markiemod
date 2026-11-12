import './shared/allow.js';

const OFFSCREEN_URL = 'offscreen.html';
let creating = null;

async function ensureOffscreen() {
  const contexts = await chrome.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'] });
  if (contexts.length) return;
  if (!creating) {
    creating = chrome.offscreen
      .createDocument({
        url: OFFSCREEN_URL,
        reasons: ['WORKERS'],
        justification: 'Runs the Stockfish engine in a Web Worker.',
      })
      .finally(() => {
        creating = null;
      });
  }
  await creating;
}

function senderAllowed(sender) {
  if (sender.id !== chrome.runtime.id) return false;
  const url = sender.url || '';
  if (url.startsWith(chrome.runtime.getURL(''))) return true; // our own offline board
  return !!globalThis.CAAllow.modeForUrl(url);
}

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg || msg.target !== 'background') return false;

  if (msg.type === 'analyze') {
    if (!senderAllowed(sender)) {
      sendResponse({ error: 'Chess Coach is turned off on this page (games against people).' });
      return false;
    }
    (async () => {
      await ensureOffscreen();
      return chrome.runtime.sendMessage({
        target: 'offscreen',
        type: 'analyze',
        fen: msg.fen,
        movetime: msg.movetime,
        multipv: msg.multipv,
      });
    })()
      .then((res) => sendResponse(res || { error: 'No response from engine.' }))
      .catch((err) => sendResponse({ error: String(err && err.message ? err.message : err) }));
    return true;
  }

  if (msg.type === 'openBoard') {
    chrome.tabs.create({ url: chrome.runtime.getURL('board/board.html') });
    return false;
  }
  return false;
});
