// Runs in the page's own JS world so it can read the FEN straight from
// chess.com's board component. It only answers on allowed pages and only
// ever reads the position – it never moves pieces.
(() => {
  if (window.__chessCoachBridge) return;
  window.__chessCoachBridge = true;

  // Same allowlist as shared/allow.js. It is copied here because Chrome injects a
  // given file only once per page, so the file can't be shared with the
  // isolated-world content script.
  const ALLOWED = /^\/(?:[a-z]{2}(?:-[a-z]{2,4})?\/)?(analysis(?:\/|$)|play\/computer(?:\/|$)|game\/computer\/)/i;

  window.addEventListener('message', (e) => {
    if (e.source !== window || !e.data || e.data.__chessCoach !== 'req') return;
    let fen = null;
    if (location.hostname === 'www.chess.com' && ALLOWED.test(location.pathname)) {
      try {
        const el = document.querySelector('wc-chess-board, chess-board');
        const game = el && el.game;
        if (game && typeof game.getFEN === 'function') fen = game.getFEN();
      } catch (_) {
        fen = null;
      }
    }
    window.postMessage({ __chessCoach: 'res', id: e.data.id, fen }, location.origin);
  });
})();
