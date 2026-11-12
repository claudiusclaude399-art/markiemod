// Tiny dependency-free board renderer (used for the preview board and the offline board).
(function (g) {
  const GLYPH = { k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟' };
  const FILES = 'abcdefgh';

  function parseFen(fen) {
    const grid = Array.from({ length: 8 }, () => Array(8).fill(null)); // grid[rank0][file0]
    const rows = String(fen || '').split(' ')[0].split('/');
    rows.forEach((row, i) => {
      const r = 7 - i;
      let f = 0;
      for (const ch of row) {
        if (/\d/.test(ch)) f += +ch;
        else {
          if (r >= 0 && f < 8) grid[r][f] = { c: ch === ch.toUpperCase() ? 'w' : 'b', t: ch.toLowerCase() };
          f++;
        }
      }
    });
    return grid;
  }

  function render(container, fen, o = {}) {
    container.textContent = '';
    const board = document.createElement('div');
    board.className = 'cam-board';
    const grid = parseFen(fen);
    const dots = new Set(o.dots || []);
    for (let row = 0; row < 8; row++) {
      for (let col = 0; col < 8; col++) {
        const f = o.flipped ? 7 - col : col;
        const r = o.flipped ? row : 7 - row;
        const sq = FILES[f] + (r + 1);
        const d = document.createElement('div');
        d.className = 'cam-sq ' + ((f + r) % 2 === 0 ? 'dark' : 'light');
        d.dataset.sq = sq;
        if (sq === o.from || sq === o.to) d.classList.add('hl');
        if (sq === o.selected) d.classList.add('sel');
        const pc = grid[r][f];
        if (pc) {
          const s = document.createElement('span');
          s.className = 'cam-pc ' + pc.c;
          s.textContent = GLYPH[pc.t] + '︎';
          d.appendChild(s);
        }
        if (dots.has(sq)) {
          const dot = document.createElement('i');
          dot.className = pc ? 'cam-ring' : 'cam-dot';
          d.appendChild(dot);
        }
        if (o.coords !== false) {
          if (col === 0) {
            const c = document.createElement('b');
            c.className = 'cam-rk';
            c.textContent = r + 1;
            d.appendChild(c);
          }
          if (row === 7) {
            const c = document.createElement('b');
            c.className = 'cam-fl';
            c.textContent = FILES[f];
            d.appendChild(c);
          }
        }
        board.appendChild(d);
      }
    }
    if (o.onSquare) {
      board.addEventListener('click', (e) => {
        const s = e.target.closest('[data-sq]');
        if (s) o.onSquare(s.dataset.sq);
      });
    }
    container.appendChild(board);
    return board;
  }

  const CSS = `
.cam-board{display:grid;grid-template-columns:repeat(8,1fr);aspect-ratio:1/1;width:100%;container-type:inline-size;user-select:none;border-radius:4px;overflow:hidden}
.cam-sq{position:relative;display:flex;align-items:center;justify-content:center;aspect-ratio:1/1}
.cam-sq.light{background:#ebecd0}.cam-sq.dark{background:#779556}
.cam-sq.hl.light{background:#f5f682}.cam-sq.hl.dark{background:#b9ca43}
.cam-sq.sel{box-shadow:inset 0 0 0 4px rgba(20,85,180,.7)}
.cam-pc{font-family:"Segoe UI Symbol","Noto Sans Symbols 2","Noto Sans Symbols","DejaVu Sans",serif;font-size:10.5cqw;line-height:1;font-variant-emoji:text}
.cam-pc.w{color:#fff;-webkit-text-stroke:.35cqw #2b2b2b;paint-order:stroke fill}
.cam-pc.b{color:#1f1f1f;-webkit-text-stroke:.2cqw #6b6b6b}
.cam-dot{position:absolute;width:30%;height:30%;border-radius:50%;background:rgba(0,0,0,.22)}
.cam-ring{position:absolute;inset:4%;border-radius:50%;border:.9cqw solid rgba(0,0,0,.22)}
.cam-rk,.cam-fl{position:absolute;font:700 2.2cqw/1 system-ui,sans-serif;opacity:.75}
.cam-rk{top:4%;left:5%}.cam-fl{bottom:4%;right:6%}
.cam-sq.light .cam-rk,.cam-sq.light .cam-fl{color:#779556}.cam-sq.dark .cam-rk,.cam-sq.dark .cam-fl{color:#ebecd0}
`;

  g.CAMini = { render, parseFen, GLYPH, CSS };
})(globalThis);
