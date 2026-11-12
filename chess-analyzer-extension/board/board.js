import { Chess } from '../lib/chess.js';

const $ = (id) => document.getElementById(id);
const game = new Chess();
let flipped = false;
let selected = null;
let lastMove = null;
let marks = [];
let analyzeSeq = 0;

const style = document.createElement('style');
style.textContent = CAMini.CSS;
document.head.appendChild(style);

const svg = CAOverlay.create();
$('overlay').appendChild(svg);

const panel = CAPanel.create({
  host: $('panel-host'),
  floating: false,
  modeText: 'Offline',
  storage: chrome.storage.local,
  onAnalyze: analyze,
  onMarks: (m) => {
    marks = m;
    CAOverlay.draw(svg, marks, flipped);
  },
  onSettings: (s, key) => {
    if (key === 'auto' && s.auto) analyze();
  },
  onPlay: (line) => {
    const mv = tryMove(line.from, line.to, line.promotion);
    if (mv) afterMove(mv);
  },
});

function message(text) {
  $('msg').textContent = text || '';
}

function draw() {
  const dots = selected ? game.moves({ square: selected, verbose: true }).map((m) => m.to) : [];
  CAMini.render($('board'), game.fen(), {
    flipped,
    from: lastMove && lastMove.from,
    to: lastMove && lastMove.to,
    selected,
    dots,
    onSquare,
  });
  CAOverlay.draw(svg, marks, flipped);
  $('fen').value = game.fen();
  if (game.isCheckmate()) message(`Checkmate. ${game.turn() === 'w' ? 'Black' : 'White'} wins.`);
  else if (game.isDraw() || game.isStalemate()) message('Draw.');
  else message(`${game.turn() === 'w' ? 'White' : 'Black'} to move${game.inCheck() ? ' (check)' : ''}.`);
}

function tryMove(from, to, promotion) {
  try {
    return game.move({ from, to, promotion: promotion || $('promo').value });
  } catch (_) {
    return null;
  }
}

function onSquare(sq) {
  if (selected && selected !== sq) {
    const mv = tryMove(selected, sq);
    if (mv) {
      selected = null;
      afterMove(mv);
      return;
    }
  }
  const p = game.get(sq);
  selected = p && p.color === game.turn() && selected !== sq ? sq : null;
  draw();
}

function afterMove(mv) {
  lastMove = mv;
  selected = null;
  marks = [];
  panel.clear('');
  draw();
  if (panel.getSettings().auto) analyze();
}

async function analyze() {
  const fen = game.fen();
  const seq = ++analyzeSeq;
  panel.setBusy(true);
  let res;
  try {
    res = await chrome.runtime.sendMessage({
      target: 'background',
      type: 'analyze',
      fen,
      movetime: panel.getSettings().movetime,
      multipv: 10,
    });
  } catch (e) {
    res = { error: String(e.message || e) };
  }
  if (!res || res.superseded || seq !== analyzeSeq || fen !== game.fen()) return;
  if (res.error) panel.clear(res.error);
  else panel.setResult(res, flipped);
}

$('new').onclick = () => {
  game.reset();
  lastMove = null;
  afterMove(null);
};
$('undo').onclick = () => {
  game.undo();
  const hist = game.history({ verbose: true });
  afterMove(hist[hist.length - 1] || null);
};
$('flip').onclick = () => {
  flipped = !flipped;
  panel.setFlipped(flipped);
  draw();
};
$('load').onclick = () => {
  const fen = $('fen').value.trim();
  try {
    game.load(fen);
    afterMove(null);
  } catch (e) {
    message('Invalid FEN: ' + e.message);
  }
};
$('copy').onclick = async () => {
  try {
    await navigator.clipboard.writeText(game.fen());
    message('FEN copied.');
  } catch (_) {
    message('Could not copy.');
  }
};

// Allow ?fen=... to open a specific position.
const startFen = new URLSearchParams(location.search).get('fen');
if (startFen) {
  try {
    game.load(startFen);
  } catch (_) {
    /* ignore bad fen */
  }
}
draw();
