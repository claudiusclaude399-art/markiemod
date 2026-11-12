import { Analyzer } from './lib/analysis.js';

const analyzer = new Analyzer(chrome.runtime.getURL('engine/stockfish-19-lite-single.js'));

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg || msg.target !== 'offscreen') return false;
  if (msg.type === 'analyze') {
    analyzer
      .analyze(msg.fen, { movetime: msg.movetime, multipv: msg.multipv })
      .then(sendResponse, (err) => sendResponse({ error: String(err && err.message ? err.message : err) }));
    return true;
  }
  return false;
});
