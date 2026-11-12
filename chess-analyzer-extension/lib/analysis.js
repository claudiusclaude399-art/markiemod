// Stockfish wrapper + move classification.
import { Chess } from './chess.js';

const VALUE = { p: 1, n: 3, b: 3, r: 5, q: 9, k: 100 };
const PV_PLIES = 10;

// Win probability (0..1) for the side to move, from centipawns (lichess curve).
export function winChance(cp) {
  return 1 / (1 + Math.exp(-0.00368208 * cp));
}

function scoreToCp(score) {
  if (score.mate != null) {
    return score.mate > 0 ? 10000 - score.mate * 10 : -10000 - score.mate * 10;
  }
  return score.cp;
}

// Classify by expected-points lost compared with the best move.
function classifyLoss(loss) {
  if (loss <= 0.02) return 'excellent';
  if (loss <= 0.05) return 'good';
  if (loss <= 0.1) return 'inaccuracy';
  if (loss <= 0.2) return 'mistake';
  return 'blunder';
}

function scoreText(score, turn) {
  const sign = turn === 'w' ? 1 : -1;
  if (score.mate != null) {
    const m = score.mate * sign;
    return m > 0 ? `#${m}` : `#-${Math.abs(m)}`;
  }
  const v = (score.cp * sign) / 100;
  return (v > 0 ? '+' : '') + v.toFixed(Math.abs(v) >= 10 ? 0 : 1);
}

// "Sacrifice" heuristic: the moved piece can be taken and we end up >= 2 points down
// on that square even after recapturing.
function isSacrifice(move) {
  const moved = move.promotion || move.piece;
  const v = VALUE[moved];
  if (moved === 'k' || v < 3) return false;
  const gained = move.captured ? VALUE[move.captured] : 0;
  const after = new Chess(move.after);
  if (after.isCheckmate()) return false;
  const captures = after
    .moves({ verbose: true })
    .filter((m) => m.to === move.to && m.captured)
    .sort((a, b) => VALUE[a.piece] - VALUE[b.piece]);
  if (!captures.length) return false;
  const cap = captures[0];
  const after2 = new Chess(cap.after);
  const canRecapture = after2.moves({ verbose: true }).some((m) => m.to === move.to && m.captured);
  const net = gained - v + (canRecapture ? VALUE[cap.piece] : 0);
  return net <= -2;
}

function parseInfo(line) {
  const t = line.split(/\s+/);
  const out = { depth: 0, multipv: 1, score: null, bound: false, pv: [] };
  for (let i = 1; i < t.length; i++) {
    switch (t[i]) {
      case 'depth':
        out.depth = +t[++i];
        break;
      case 'multipv':
        out.multipv = +t[++i];
        break;
      case 'score': {
        const kind = t[++i];
        const val = +t[++i];
        out.score = kind === 'mate' ? { mate: val } : { cp: val };
        break;
      }
      case 'lowerbound':
      case 'upperbound':
        out.bound = true;
        break;
      case 'pv':
        out.pv = t.slice(i + 1);
        i = t.length;
        break;
      default:
        break;
    }
  }
  return out;
}

function uciToObj(uci) {
  return { from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci[4] || undefined };
}

export class Analyzer {
  constructor(workerUrl) {
    this.workerUrl = workerUrl;
    this.worker = null;
    this.ready = null;
    this.listeners = new Set();
    this.busy = null;
    this.seq = 0;
  }

  _send(cmd) {
    this.worker.postMessage(cmd);
  }

  _waitFor(cmd, token) {
    return new Promise((resolve) => {
      const f = (l) => {
        if (l.startsWith(token)) {
          this.listeners.delete(f);
          resolve();
        }
      };
      this.listeners.add(f);
      this._send(cmd);
    });
  }

  _init() {
    if (this.ready) return this.ready;
    this.worker = new Worker(this.workerUrl);
    this.worker.onmessage = (e) => {
      const text = typeof e.data === 'string' ? e.data : String(e.data);
      for (const l of text.split('\n')) for (const f of [...this.listeners]) f(l);
    };
    this.worker.onerror = (e) => console.error('Stockfish worker error', e);
    this.ready = this._waitFor('uci', 'uciok').then(() => this._waitFor('isready', 'readyok'));
    return this.ready;
  }

  async analyze(fen, { movetime = 1500, multipv = 10 } = {}) {
    let game;
    try {
      game = new Chess(fen);
    } catch (e) {
      return { error: 'Could not read the position: ' + e.message };
    }
    const base = { fen: game.fen(), turn: game.turn(), moveNumber: game.moveNumber() };
    if (game.isGameOver()) {
      const reason = game.isCheckmate() ? 'Checkmate' : game.isStalemate() ? 'Stalemate' : 'Draw';
      return { ...base, gameOver: true, reason, lines: [] };
    }

    await this._init();
    const token = ++this.seq;
    while (this.busy) {
      if (token !== this.seq) return { superseded: true };
      this._send('stop');
      await this.busy.catch(() => {});
    }
    if (token !== this.seq) return { superseded: true };

    // No awaits between the check above and claiming `busy`, so runs never overlap.
    const legal = game.moves().length;
    const time = Math.max(200, Math.min(10000, movetime | 0));
    this.busy = this._run(base.fen, Math.max(1, Math.min(multipv | 0 || 10, legal)), time);
    let raw;
    try {
      raw = await this.busy;
    } finally {
      this.busy = null;
    }
    if (token !== this.seq) return { superseded: true };
    return this._build(base, raw);
  }

  async _run(fen, multipv, movetime) {
    this._send(`setoption name MultiPV value ${multipv}`);
    await this._waitFor('isready', 'readyok');
    return new Promise((resolve) => {
      const lines = new Map();
      const f = (l) => {
        if (l.startsWith('info') && l.includes(' pv ') && l.includes(' score ')) {
          const info = parseInfo(l);
          if (!info.score || !info.pv.length) return;
          const prev = lines.get(info.multipv);
          if (info.bound && prev) return;
          lines.set(info.multipv, info);
        } else if (l.startsWith('bestmove')) {
          this.listeners.delete(f);
          resolve({ lines, bestmove: l.split(/\s+/)[1] });
        }
      };
      this.listeners.add(f);
      this._send(`position fen ${fen}`);
      this._send(`go movetime ${movetime}`);
    });
  }

  _build(base, raw) {
    // MultiPV slots can re-order between depths, so the same first move may
    // show up twice when the search is stopped. Keep the deepest entry per move.
    const byMove = new Map();
    for (const i of raw.lines.values()) {
      const prev = byMove.get(i.pv[0]);
      if (!prev || i.depth > prev.depth || (i.depth === prev.depth && i.multipv < prev.multipv)) byMove.set(i.pv[0], i);
    }
    const infos = [...byMove.values()]
      .map((i) => ({ ...i, num: scoreToCp(i.score) }))
      .sort((a, b) => b.num - a.num);
    if (!infos.length) return { ...base, lines: [], error: 'Engine returned no lines.' };

    const bestWin = winChance(infos[0].num);
    const lines = [];
    infos.forEach((info, idx) => {
      const g = new Chess(base.fen);
      const pv = [];
      let first = null;
      for (const uci of info.pv.slice(0, PV_PLIES)) {
        let mv;
        try {
          mv = g.move(uciToObj(uci));
        } catch (_) {
          break;
        }
        if (!first) first = mv;
        pv.push({ san: mv.san, from: mv.from, to: mv.to, color: mv.color, fen: mv.after });
      }
      if (!first) return;
      const win = winChance(info.num);
      const loss = Math.max(0, bestWin - win);
      lines.push({
        rank: idx + 1,
        uci: info.pv[0],
        san: first.san,
        from: first.from,
        to: first.to,
        piece: first.piece,
        promotion: first.promotion,
        depth: info.depth,
        loss,
        win,
        whiteWin: base.turn === 'w' ? win : 1 - win,
        scoreText: scoreText(info.score, base.turn),
        label: idx === 0 ? 'best' : classifyLoss(loss),
        sacrifice: isSacrifice(first),
        pv,
      });
    });

    if (lines.length) {
      const top = lines[0];
      const secondLoss = lines[1] ? lines[1].loss : 0;
      if (top.sacrifice && top.win >= 0.4 && bestWin < 0.97) top.label = 'brilliant';
      else if (lines[1] && secondLoss > 0.1 && top.win >= 0.35) top.label = 'great';
    }

    return { ...base, depth: lines[0] ? lines[0].depth : 0, lines };
  }
}
