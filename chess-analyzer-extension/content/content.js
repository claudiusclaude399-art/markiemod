// chess.com integration. Only does anything on pages CAAllow approves
// (Analysis board / Game Review and games vs computer bots).
(() => {
  if (window.__chessCoachContent) return;
  window.__chessCoachContent = true;

  const MODE_TEXT = { analysis: 'Analysis', bot: 'vs Bot' };
  let mode = null;
  let enabled = true;
  let panel = null;
  let host = null;
  let overlayWrap = null;
  let svg = null;
  let marks = [];
  let observer = null;
  let observedBoard = null;
  let lastFen = null;
  let lastFlipped = false;
  let debounce = null;
  let rafId = 0;
  let reqId = 0;

  // ---------- board reading ----------
  function findBoard() {
    return (
      document.querySelector('wc-chess-board') ||
      document.querySelector('chess-board') ||
      document.querySelector('#board-single, #board-play-computer, #board-analysis-board')
    );
  }

  function askPageForFen() {
    return new Promise((resolve) => {
      const id = 'cc' + ++reqId;
      const done = (fen) => {
        window.removeEventListener('message', onMsg);
        clearTimeout(t);
        resolve(fen);
      };
      const onMsg = (e) => {
        if (e.source === window && e.data && e.data.__chessCoach === 'res' && e.data.id === id) done(e.data.fen || null);
      };
      const t = setTimeout(() => done(null), 300);
      window.addEventListener('message', onMsg);
      window.postMessage({ __chessCoach: 'req', id }, location.origin);
    });
  }

  function squareOf(node) {
    for (const c of node.classList) {
      const m = /^square-(\d)(\d)$/.exec(c);
      if (m) return [+m[1] - 1, +m[2] - 1];
    }
    return null;
  }

  // Fallback: rebuild the FEN from the piece elements (side to move from the last-move highlight).
  function fenFromDom(board) {
    const grid = Array.from({ length: 8 }, () => Array(8).fill(null));
    let count = 0;
    board.querySelectorAll('.piece').forEach((p) => {
      let type = null;
      for (const c of p.classList) if (/^[wb][prnbqk]$/.test(c)) type = c;
      const sq = squareOf(p);
      if (type && sq && sq[0] >= 0 && sq[0] < 8 && sq[1] >= 0 && sq[1] < 8) {
        grid[sq[1]][sq[0]] = type;
        count++;
      }
    });
    if (!count) return null;
    let turn = 'w';
    for (const hl of board.querySelectorAll('.highlight')) {
      const sq = squareOf(hl);
      const pc = sq && grid[sq[1]][sq[0]];
      if (pc) {
        turn = pc[0] === 'w' ? 'b' : 'w';
        break;
      }
    }
    const rows = [];
    for (let r = 7; r >= 0; r--) {
      let row = '';
      let empty = 0;
      for (let f = 0; f < 8; f++) {
        const pc = grid[r][f];
        if (!pc) {
          empty++;
          continue;
        }
        if (empty) row += empty;
        empty = 0;
        row += pc[0] === 'w' ? pc[1].toUpperCase() : pc[1];
      }
      if (empty) row += empty;
      rows.push(row);
    }
    let castle = '';
    if (grid[0][4] === 'wk') {
      if (grid[0][7] === 'wr') castle += 'K';
      if (grid[0][0] === 'wr') castle += 'Q';
    }
    if (grid[7][4] === 'bk') {
      if (grid[7][7] === 'br') castle += 'k';
      if (grid[7][0] === 'br') castle += 'q';
    }
    return `${rows.join('/')} ${turn} ${castle || '-'} - 0 1`;
  }

  async function readPosition() {
    const board = findBoard();
    if (!board) return null;
    const flipped = board.classList.contains('flipped');
    let fen = await askPageForFen();
    if (!fen) fen = fenFromDom(board);
    return fen ? { fen, flipped, board } : null;
  }

  // ---------- overlay ----------
  function ensureOverlay() {
    if (overlayWrap) return;
    overlayWrap = document.createElement('div');
    overlayWrap.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483000;display:none';
    svg = CAOverlay.create();
    overlayWrap.appendChild(svg);
    document.documentElement.appendChild(overlayWrap);
  }

  function syncOverlay() {
    rafId = 0;
    if (!overlayWrap) return;
    const board = findBoard();
    if (!board || !marks.length) {
      overlayWrap.style.display = 'none';
      return;
    }
    const r = board.getBoundingClientRect();
    Object.assign(overlayWrap.style, { display: 'block', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
    const flipped = board.classList.contains('flipped');
    if (flipped !== lastFlipped) {
      lastFlipped = flipped;
      CAOverlay.draw(svg, marks, flipped);
    }
    rafId = requestAnimationFrame(syncOverlay);
  }

  function setMarks(m) {
    marks = m || [];
    ensureOverlay();
    const board = findBoard();
    lastFlipped = !!(board && board.classList.contains('flipped'));
    CAOverlay.draw(svg, marks, lastFlipped);
    if (!rafId) rafId = requestAnimationFrame(syncOverlay);
  }

  // ---------- analysis ----------
  async function analyze(fromAuto) {
    if (!panel || !mode) return;
    const pos = await readPosition();
    if (!pos) {
      panel.clear('Could not find the board on this page.');
      return;
    }
    lastFen = pos.fen;
    const settings = panel.getSettings();
    if (fromAuto && mode === 'bot') {
      const myColor = pos.flipped ? 'b' : 'w';
      if (pos.fen.split(' ')[1] !== myColor) {
        panel.clear('Waiting for the bot to move…');
        return;
      }
    }
    panel.setBusy(true);
    let res;
    try {
      res = await chrome.runtime.sendMessage({
        target: 'background',
        type: 'analyze',
        fen: pos.fen,
        movetime: settings.movetime,
        multipv: 10,
      });
    } catch (e) {
      res = { error: 'Extension was reloaded. Refresh this page.' };
    }
    if (!res || res.superseded) return;
    if (pos.fen !== lastFen) return; // position moved on while thinking
    panel.setBusy(false);
    if (res.error) panel.clear(res.error);
    else panel.setResult(res, pos.flipped);
  }

  function onBoardChange() {
    clearTimeout(debounce);
    debounce = setTimeout(async () => {
      if (!panel) return;
      const pos = await readPosition();
      if (!pos || pos.fen === lastFen) return;
      lastFen = pos.fen;
      if (panel.getSettings().auto) analyze(true);
      else panel.clear('Position changed. Press Analyze.');
    }, 250);
  }

  function watchBoard() {
    const board = findBoard();
    if (board === observedBoard) return;
    if (observer) observer.disconnect();
    observedBoard = board;
    if (!board) return;
    observer = new MutationObserver(onBoardChange);
    observer.observe(board, { subtree: true, childList: true, attributes: true, attributeFilter: ['class'] });
  }

  // ---------- lifecycle ----------
  function setup() {
    if (panel) {
      panel.setMode(MODE_TEXT[mode]);
      return;
    }
    host = document.createElement('div');
    host.style.cssText = 'position:fixed;z-index:2147483001;top:90px;right:18px';
    document.documentElement.appendChild(host);
    panel = CAPanel.create({
      host,
      floating: true,
      modeText: MODE_TEXT[mode],
      storage: chrome.storage && chrome.storage.local,
      onAnalyze: () => analyze(false),
      onMarks: setMarks,
      onSettings: (s, key) => {
        if (key === 'auto' && s.auto) analyze(true);
      },
    });
    lastFen = null;
    panel.ready.then(() => {
      if (panel && panel.getSettings().auto) analyze(true);
    });
  }

  function teardown() {
    if (panel) panel.destroy();
    panel = null;
    host = null;
    setMarks([]);
    if (observer) observer.disconnect();
    observer = null;
    observedBoard = null;
    lastFen = null;
  }

  function check() {
    const m = enabled ? CAAllow.modeForUrl(location.href) : null;
    if (m !== mode) {
      mode = m;
      if (!mode) teardown();
      else setup();
    }
    if (mode) watchBoard();
  }

  chrome.storage.local.get('chessCoachEnabled').then((v) => {
    enabled = v.chessCoachEnabled !== false;
    check();
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes.chessCoachEnabled) {
      enabled = changes.chessCoachEnabled.newValue !== false;
      check();
    }
  });
  setInterval(check, 1000);
  window.addEventListener('popstate', check);
})();
