const box = document.getElementById('enabled');
const here = document.getElementById('here');

chrome.storage.local.get('chessCoachEnabled').then((v) => {
  box.checked = v.chessCoachEnabled !== false;
});
box.addEventListener('change', () => chrome.storage.local.set({ chessCoachEnabled: box.checked }));

document.getElementById('board').addEventListener('click', () => {
  chrome.tabs.create({ url: chrome.runtime.getURL('board/board.html') });
  window.close();
});

chrome.tabs.query({ active: true, currentWindow: true }).then(([tab]) => {
  const url = (tab && tab.url) || '';
  const mode = CAAllow.modeForUrl(url);
  if (mode === 'analysis') here.textContent = '✅ Active here: Analysis board.';
  else if (mode === 'bot') here.textContent = '✅ Active here: game vs bot.';
  else if (/^https:\/\/(www\.)?chess\.com\//.test(url)) here.textContent = '⛔ Not available on this chess.com page.';
  else here.textContent = 'Open chess.com Analysis or a bot game, or use the offline board.';
});
