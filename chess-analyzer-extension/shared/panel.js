// The coach panel: tiers, move cards, hints and the line preview.
// Shared by the chess.com content script and the offline board page.
(function (g) {
  const LABELS = {
    brilliant: { text: 'Brilliant', sym: '!!', color: '#1baca6' },
    great: { text: 'Great', sym: '!', color: '#5c8bb0' },
    best: { text: 'Best', sym: '★', color: '#81b64c' },
    excellent: { text: 'Excellent', sym: '↑', color: '#96bc4b' },
    good: { text: 'Good', sym: '✓', color: '#95b776' },
    inaccuracy: { text: 'Inaccuracy', sym: '?!', color: '#f7c631' },
    mistake: { text: 'Mistake', sym: '?', color: '#e58f2a' },
    blunder: { text: 'Blunder', sym: '??', color: '#ca3431' },
  };

  const TIERS = [
    { id: 'brilliant', name: 'Brilliant', count: '1 move', color: '#1baca6' },
    { id: 'excellent', name: 'Excellent', count: '2 moves', color: '#96bc4b' },
    { id: 'good', name: 'Good', count: '3 moves', color: '#95b776' },
    { id: 'all', name: 'All', count: 'every line', color: '#8b8987' },
  ];

  const PIECE = { p: 'pawn', n: 'knight', b: 'bishop', r: 'rook', q: 'queen', k: 'king' };
  const TIMES = [
    [500, '0.5s'],
    [1000, '1s'],
    [1500, '1.5s'],
    [3000, '3s'],
    [5000, '5s'],
  ];
  const DEFAULTS = { tier: 'brilliant', movetime: 1500, auto: false, hide: false, collapsed: false, x: null, y: null };
  const STORE_KEY = 'chessCoachPanel';

  function h(tag, attrs, ...kids) {
    const n = document.createElement(tag);
    if (attrs) {
      for (const k in attrs) {
        if (k === 'class') n.className = attrs[k];
        else if (k.startsWith('on')) n.addEventListener(k.slice(2), attrs[k]);
        else if (attrs[k] !== false && attrs[k] != null) n.setAttribute(k, attrs[k] === true ? '' : attrs[k]);
      }
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) n.append(kid);
    return n;
  }

  // Pick the moves to show for a tier.
  function pick(result, tier) {
    const L = (result && result.lines) || [];
    if (!L.length) return { lines: [], note: '' };
    const top = L[0];
    if (tier === 'brilliant') {
      const note =
        top.label === 'brilliant'
          ? 'A sacrifice that works. The engine likes it.'
          : top.label === 'great'
          ? 'Only move: everything else is clearly worse.'
          : 'There is no sacrifice here, so this is just the engine’s top move.';
      return { lines: [top], note };
    }
    const rest = L.slice(1);
    if (tier === 'excellent') {
      const lines = rest.filter((l) => l.label === 'excellent').slice(0, 2);
      const note = !lines.length
        ? 'No other excellent moves here. Only the top move keeps the position.'
        : lines.length < 2
        ? 'Only one alternative is excellent in this position.'
        : 'Near-best alternatives to the top move.';
      return { lines, note };
    }
    if (tier === 'good') {
      const goods = rest.filter((l) => l.label === 'good');
      const fill = rest.filter((l) => l.label === 'excellent').reverse();
      const lines = [...goods, ...fill].slice(0, 3).sort((a, b) => a.rank - b.rank);
      const note = !lines.length
        ? 'Every alternative to the top move loses something here.'
        : lines.length < 3
        ? `Only ${lines.length} solid alternative${lines.length > 1 ? 's' : ''} in this position.`
        : 'Solid, playable moves that are not the engine’s top choice.';
      return { lines, note };
    }
    return { lines: L, note: 'All the engine’s candidate moves, best first.' };
  }

  function movePrefix(result, plyIndex) {
    const whiteFirst = result.turn === 'w';
    const absolute = (result.moveNumber - 1) * 2 + (whiteFirst ? 0 : 1) + plyIndex;
    const num = Math.floor(absolute / 2) + 1;
    return absolute % 2 === 0 ? `${num}.` : `${num}…`;
  }

  const CSS = `
:host{all:initial}
*{box-sizing:border-box}
.panel{font:13px/1.35 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:#e8e6e3;background:#262421;border:1px solid #3c3a37;border-radius:10px;box-shadow:0 10px 30px rgba(0,0,0,.45);width:320px;overflow:hidden}
.panel.docked{width:100%;box-shadow:none}
.hdr{display:flex;align-items:center;gap:8px;padding:9px 10px;background:#1f1d1a;cursor:grab;user-select:none}
.docked .hdr{cursor:default}
.hdr .logo{width:20px;height:20px;border-radius:5px;background:#81b64c;color:#fff;font-weight:800;display:grid;place-items:center;font-size:13px}
.hdr .title{font-weight:700;flex:1}
.hdr .mode{font-size:11px;color:#a7a6a2;background:#312e2b;padding:2px 7px;border-radius:999px}
.ib{background:none;border:0;color:#a7a6a2;cursor:pointer;font-size:15px;padding:0 4px;line-height:1}
.ib:hover{color:#fff}
.body{padding:10px;display:flex;flex-direction:column;gap:9px;max-height:calc(100vh - 140px);overflow:auto}
.docked .body{max-height:none}
.collapsed .body{display:none}
.evalrow{display:flex;align-items:center;gap:8px}
.evalbar{flex:1;height:10px;border-radius:5px;background:#403d39;overflow:hidden}
.evalbar i{display:block;height:100%;background:#f0f0f0;transition:width .3s}
.evaltxt{font-weight:700;min-width:46px;text-align:right;font-variant-numeric:tabular-nums}
.status{font-size:12px;color:#a7a6a2;min-height:16px}
.tiers{display:grid;grid-template-columns:repeat(4,1fr);gap:5px}
.tier{border:1px solid #3c3a37;background:#312e2b;color:#e8e6e3;border-radius:7px;padding:6px 2px;cursor:pointer;text-align:center;font:inherit}
.tier b{display:block;font-size:12px}
.tier span{display:block;font-size:10px;color:#a7a6a2}
.tier.on{border-color:var(--c);box-shadow:inset 0 0 0 1px var(--c);background:#3a3733}
.tier:hover{background:#3a3733}
.row{display:flex;gap:6px;align-items:center;flex-wrap:wrap}
.btn{background:#81b64c;color:#fff;border:0;border-radius:7px;padding:7px 11px;font:600 13px system-ui,sans-serif;cursor:pointer;box-shadow:0 3px 0 #45753c}
.btn:active{transform:translateY(2px);box-shadow:0 1px 0 #45753c}
.btn.sec{background:#454241;box-shadow:0 3px 0 #2b2927}
.btn[disabled]{opacity:.6;cursor:default}
.chk{display:flex;align-items:center;gap:4px;font-size:12px;color:#c9c7c3;cursor:pointer}
select{background:#312e2b;color:#e8e6e3;border:1px solid #3c3a37;border-radius:6px;padding:4px;font:inherit}
.note{font-size:12px;color:#c9c7c3;background:#2f2c29;border-left:3px solid var(--c,#81b64c);padding:6px 8px;border-radius:4px}
.cards{display:flex;flex-direction:column;gap:5px}
.card{display:flex;align-items:center;gap:8px;padding:7px 8px;background:#312e2b;border:1px solid transparent;border-radius:8px;cursor:pointer}
.card:hover{background:#3a3733}
.card.sel{border-color:var(--c)}
.sym{width:24px;height:24px;border-radius:50%;background:var(--c);color:#fff;font-weight:800;font-size:12px;display:grid;place-items:center;flex:none}
.mv{flex:1;min-width:0}
.mv b{font-size:14px}
.mv small{display:block;color:#a7a6a2;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.sc{font-weight:700;font-variant-numeric:tabular-nums}
.hidden{font-size:12px;color:#a7a6a2;text-align:center;padding:10px;border:1px dashed #4a4744;border-radius:8px}
.preview{display:flex;flex-direction:column;gap:6px;border-top:1px solid #3c3a37;padding-top:9px}
.preview .cap{font-size:11px;text-transform:uppercase;letter-spacing:.05em;color:#a7a6a2}
.mini{width:100%;max-width:300px;align-self:center}
.pvctl{display:flex;gap:4px;justify-content:center}
.pvctl .btn{padding:4px 10px}
.pv{font-size:12px;line-height:1.7;color:#c9c7c3}
.pv span{cursor:pointer;padding:1px 3px;border-radius:4px}
.pv span:hover{background:#3a3733}
.pv span.on{background:#81b64c;color:#fff}
.pv em{font-style:normal;color:#8b8987;margin-right:2px}
.spin{display:inline-block;width:11px;height:11px;border:2px solid #a7a6a2;border-top-color:transparent;border-radius:50%;animation:s .8s linear infinite;vertical-align:-1px;margin-right:5px}
@keyframes s{to{transform:rotate(360deg)}}
`;

  function create(opts) {
    const root = opts.host.attachShadow({ mode: 'open' });
    root.append(h('style', null, CSS + (g.CAMini ? g.CAMini.CSS : '')));

    let settings = { ...DEFAULTS };
    let result = null;
    let flipped = false;
    let selected = null;
    let ply = 1;
    let hoverLine = null;
    let hintLevel = 0;
    let revealed = false;
    let playTimer = null;
    let busy = false;
    let statusText = '';

    const els = {};
    const panel = h('div', { class: 'panel' + (opts.floating ? '' : ' docked') });
    const hdr = h(
      'div',
      { class: 'hdr' },
      h('div', { class: 'logo' }, '♞'),
      h('div', { class: 'title' }, 'Chess Coach'),
      (els.mode = h('div', { class: 'mode' }, opts.modeText || '')),
      opts.floating ? (els.min = h('button', { class: 'ib', title: 'Minimise', onclick: () => setSetting('collapsed', !settings.collapsed) }, '–')) : null
    );
    const body = h('div', { class: 'body' });
    panel.append(hdr, body);
    root.append(panel);

    // eval + status
    els.evalBar = h('i', { style: 'width:50%' });
    els.evalTxt = h('div', { class: 'evaltxt' }, '–');
    body.append(h('div', { class: 'evalrow' }, h('div', { class: 'evalbar', title: 'White’s winning chances' }, els.evalBar), els.evalTxt));
    els.status = h('div', { class: 'status' });
    body.append(els.status);

    // tiers
    els.tiers = h('div', { class: 'tiers' });
    for (const t of TIERS) {
      const b = h('button', { class: 'tier', style: `--c:${t.color}`, onclick: () => setSetting('tier', t.id) }, h('b', null, t.name), h('span', null, t.count));
      b.dataset.id = t.id;
      els.tiers.append(b);
    }
    body.append(els.tiers);

    // actions
    els.analyze = h('button', { class: 'btn', onclick: () => opts.onAnalyze && opts.onAnalyze() }, 'Analyze');
    els.hint = h('button', { class: 'btn sec', onclick: nextHint, title: 'Each press gives a bigger clue' }, '💡 Hint');
    els.reveal = h('button', { class: 'btn sec', onclick: () => { revealed = true; hintLevel = 0; renderCards(); updateMarks(); } }, 'Reveal');
    body.append(h('div', { class: 'row' }, els.analyze, els.hint, els.reveal));

    els.time = h('select', { onchange: (e) => setSetting('movetime', +e.target.value), title: 'Think time per position' });
    for (const [v, l] of TIMES) els.time.append(h('option', { value: v }, l));
    els.auto = h('input', { type: 'checkbox', onchange: (e) => setSetting('auto', e.target.checked) });
    els.hide = h('input', { type: 'checkbox', onchange: (e) => setSetting('hide', e.target.checked) });
    body.append(
      h(
        'div',
        { class: 'row' },
        h('label', { class: 'chk', title: 'Re-analyze automatically whenever the position changes' }, els.auto, 'Auto'),
        h('label', { class: 'chk', title: 'Hide the answers until you press Hint or Reveal' }, els.hide, 'Hint mode'),
        h('span', { style: 'flex:1' }),
        h('label', { class: 'chk' }, 'Think', els.time)
      )
    );

    els.note = h('div', { class: 'note' });
    els.cards = h('div', { class: 'cards' });
    body.append(els.note, els.cards);

    // preview
    els.mini = h('div', { class: 'mini' });
    els.pv = h('div', { class: 'pv' });
    els.playBtn = h('button', { class: 'btn sec', title: 'Play through the line', onclick: togglePlay }, '▶');
    els.useBtn = opts.onPlay ? h('button', { class: 'btn', onclick: () => selected && opts.onPlay(selected) }, 'Play this move') : null;
    els.preview = h(
      'div',
      { class: 'preview' },
      (els.cap = h('div', { class: 'cap' }, 'Preview')),
      els.mini,
      h(
        'div',
        { class: 'pvctl' },
        h('button', { class: 'btn sec', title: 'Start', onclick: () => setPly(0) }, '⏮'),
        h('button', { class: 'btn sec', title: 'Back', onclick: () => setPly(ply - 1) }, '◀'),
        els.playBtn,
        h('button', { class: 'btn sec', title: 'Forward', onclick: () => setPly(ply + 1) }, '▶ '),
        h('button', { class: 'btn sec', title: 'End', onclick: () => setPly(99) }, '⏭')
      ),
      els.pv,
      els.useBtn
    );
    body.append(els.preview);

    // dragging (floating mode)
    if (opts.floating) {
      let drag = null;
      hdr.addEventListener('mousedown', (e) => {
        if (e.target.closest('button')) return;
        const r = opts.host.getBoundingClientRect();
        drag = { dx: e.clientX - r.left, dy: e.clientY - r.top };
        e.preventDefault();
      });
      window.addEventListener('mousemove', (e) => {
        if (!drag) return;
        const x = Math.max(0, Math.min(window.innerWidth - 80, e.clientX - drag.dx));
        const y = Math.max(0, Math.min(window.innerHeight - 40, e.clientY - drag.dy));
        place(x, y);
      });
      window.addEventListener('mouseup', () => {
        if (!drag) return;
        drag = null;
        const r = opts.host.getBoundingClientRect();
        settings.x = Math.round(r.left);
        settings.y = Math.round(r.top);
        save();
      });
    }

    function place(x, y) {
      if (!opts.floating) return;
      const s = opts.host.style;
      if (x == null || y == null) {
        s.left = 'auto';
        s.top = '90px';
        s.right = '18px';
      } else {
        s.right = 'auto';
        s.left = Math.min(x, window.innerWidth - 80) + 'px';
        s.top = Math.min(y, window.innerHeight - 40) + 'px';
      }
    }

    function save() {
      if (opts.storage) opts.storage.set({ [STORE_KEY]: settings }).catch(() => {});
    }

    function setSetting(k, v) {
      settings[k] = v;
      save();
      if (k === 'tier' || k === 'hide') {
        hintLevel = 0;
        revealed = false;
        selected = null;
      }
      syncControls();
      renderCards();
      updateMarks();
      if (opts.onSettings) opts.onSettings({ ...settings }, k);
    }

    function syncControls() {
      for (const b of els.tiers.children) b.classList.toggle('on', b.dataset.id === settings.tier);
      els.time.value = String(settings.movetime);
      els.auto.checked = !!settings.auto;
      els.hide.checked = !!settings.hide;
      panel.classList.toggle('collapsed', !!settings.collapsed);
      if (els.min) els.min.textContent = settings.collapsed ? '+' : '–';
      els.reveal.style.display = settings.hide && !revealed ? '' : 'none';
    }

    function answersHidden() {
      return settings.hide && !revealed;
    }

    function hintLine() {
      const p = pick(result, settings.tier);
      return p.lines[0] || (result && result.lines[0]) || null;
    }

    function moveMark(line) {
      const lab = LABELS[line.label] || LABELS.good;
      return { kind: 'move', from: line.from, to: line.to, color: lab.color, badge: line.scoreText };
    }

    function updateMarks() {
      if (!opts.onMarks) return;
      let marks = [];
      if (result && result.lines.length) {
        if (hoverLine) marks = [moveMark(hoverLine)];
        else if (hintLevel === 1) {
          const l = hintLine();
          if (l) marks = [{ kind: 'square', sq: l.from, color: '#f7c631', badge: '?' }];
        } else if (hintLevel === 2) {
          const l = hintLine();
          if (l) marks = [{ ...moveMark(l), badge: '' }];
        } else if (selected) marks = [moveMark(selected)];
        else if (!answersHidden()) {
          const shown = pick(result, settings.tier).lines.slice(0, settings.tier === 'all' ? 3 : 3);
          marks = shown.map(moveMark);
        }
      }
      opts.onMarks(marks);
    }

    function nextHint() {
      if (!result || !result.lines.length) {
        if (opts.onAnalyze) opts.onAnalyze();
        return;
      }
      const l = hintLine();
      if (!l) return;
      hintLevel = Math.min(3, hintLevel + 1);
      const name = PIECE[l.piece] || 'piece';
      if (hintLevel === 1) setStatus(`Hint 1/3: look at your ${name} on ${l.from}.`);
      else if (hintLevel === 2) setStatus(`Hint 2/3: ${name} from ${l.from} to ${l.to}.`);
      else {
        setStatus(`Hint 3/3: ${l.san}. The whole line is in the preview.`);
        revealed = true;
        selectLine(l);
        return;
      }
      updateMarks();
    }

    function renderCards() {
      els.cards.textContent = '';
      els.note.style.display = 'none';
      els.preview.style.display = 'none';
      syncControls();
      if (!result) {
        els.cards.append(h('div', { class: 'hidden' }, busy ? 'Thinking…' : 'Press Analyze to see move tiers.'));
        return;
      }
      if (result.gameOver) {
        els.cards.append(h('div', { class: 'hidden' }, `${result.reason}. The game is over.`));
        return;
      }
      const p = pick(result, settings.tier);
      const tier = TIERS.find((t) => t.id === settings.tier);
      if (answersHidden()) {
        els.cards.append(h('div', { class: 'hidden' }, '🙈 Answers hidden. Press Hint for a clue, or Reveal.'));
        return;
      }
      if (p.note) {
        els.note.textContent = p.note;
        els.note.style.setProperty('--c', tier.color);
        els.note.style.display = '';
      }
      for (const line of p.lines) {
        const lab = LABELS[line.label] || LABELS.good;
        const pvPreview = line.pv
          .slice(1, 5)
          .map((m) => m.san)
          .join(' ');
        const card = h(
          'div',
          {
            class: 'card' + (selected && selected.uci === line.uci ? ' sel' : ''),
            style: `--c:${lab.color}`,
            title: 'Hover to preview on the board, click to step through the line',
            onmouseenter: () => {
              hoverLine = line;
              updateMarks();
            },
            onmouseleave: () => {
              hoverLine = null;
              updateMarks();
            },
            onclick: () => selectLine(line),
          },
          h('div', { class: 'sym' }, lab.sym),
          h('div', { class: 'mv' }, h('b', null, `${movePrefix(result, 0)} ${line.san}`), h('small', null, `${lab.text}${pvPreview ? ' · ' + pvPreview : ''}`)),
          h('div', { class: 'sc' }, line.scoreText)
        );
        els.cards.append(card);
      }
      if (selected) renderPreview();
    }

    function selectLine(line) {
      stopPlay();
      selected = line;
      ply = 1;
      renderCards();
      updateMarks();
    }

    function setPly(n) {
      if (!selected) return;
      ply = Math.max(0, Math.min(selected.pv.length, n));
      renderPreview();
    }

    function togglePlay() {
      if (playTimer) return stopPlay();
      if (!selected) return;
      if (ply >= selected.pv.length) ply = 0;
      els.playBtn.textContent = '⏸';
      playTimer = setInterval(() => {
        if (!selected || ply >= selected.pv.length) return stopPlay();
        setPly(ply + 1);
      }, 700);
    }

    function stopPlay() {
      if (playTimer) clearInterval(playTimer);
      playTimer = null;
      els.playBtn.textContent = '▶';
    }

    function renderPreview() {
      if (!selected || !result) {
        els.preview.style.display = 'none';
        return;
      }
      els.preview.style.display = '';
      const lab = LABELS[selected.label] || LABELS.good;
      els.cap.textContent = `Preview · ${selected.san} (${lab.text}, ${selected.scoreText})`;
      const step = ply > 0 ? selected.pv[ply - 1] : null;
      g.CAMini.render(els.mini, step ? step.fen : result.fen, { flipped, from: step && step.from, to: step && step.to });
      els.pv.textContent = '';
      selected.pv.forEach((m, i) => {
        const pre = movePrefix(result, i);
        if (i === 0 || pre.endsWith('.')) els.pv.append(h('em', null, pre));
        els.pv.append(h('span', { class: i + 1 === ply ? 'on' : '', onclick: () => setPly(i + 1) }, m.san), ' ');
      });
    }

    function setStatus(t) {
      statusText = t || '';
      els.status.textContent = '';
      if (busy) els.status.append(h('span', { class: 'spin' }));
      els.status.append(statusText);
    }

    function setEval() {
      const top = result && result.lines && result.lines[0];
      if (!top) {
        els.evalTxt.textContent = result && result.gameOver ? result.reason : '–';
        els.evalBar.style.width = '50%';
        return;
      }
      els.evalTxt.textContent = top.scoreText;
      els.evalBar.style.width = (top.whiteWin * 100).toFixed(1) + '%';
    }

    const api = {
      setMode(text) {
        els.mode.textContent = text || '';
      },
      setBusy(b) {
        busy = !!b;
        els.analyze.textContent = busy ? 'Thinking…' : 'Analyze';
        setStatus(statusText);
        if (busy && !result) renderCards();
      },
      setStatus,
      setFlipped(f) {
        flipped = !!f;
        renderPreview();
      },
      setResult(r, f) {
        stopPlay();
        result = r;
        flipped = !!f;
        selected = null;
        hoverLine = null;
        hintLevel = 0;
        revealed = false;
        busy = false;
        els.analyze.textContent = 'Analyze';
        const side = r.turn === 'w' ? 'White' : 'Black';
        setStatus(r.gameOver ? r.reason : `${side} to move · depth ${r.depth} · ${r.lines.length} lines`);
        setEval();
        renderCards();
        updateMarks();
      },
      clear(status) {
        stopPlay();
        result = null;
        selected = null;
        hoverLine = null;
        hintLevel = 0;
        revealed = false;
        busy = false;
        els.analyze.textContent = 'Analyze';
        setEval();
        setStatus(status || '');
        renderCards();
        updateMarks();
      },
      getSettings() {
        return { ...settings };
      },
      ready: (async () => {
        if (opts.storage) {
          try {
            const got = await opts.storage.get(STORE_KEY);
            if (got && got[STORE_KEY]) settings = { ...DEFAULTS, ...got[STORE_KEY] };
          } catch (_) {
            /* storage unavailable: defaults */
          }
        }
        place(settings.x, settings.y);
        syncControls();
        renderCards();
        setEval();
      })(),
      destroy() {
        stopPlay();
        if (opts.onMarks) opts.onMarks([]);
        opts.host.remove();
      },
    };
    place(null, null);
    syncControls();
    renderCards();
    return api;
  }

  g.CAPanel = { create, pick, LABELS, TIERS };
})(globalThis);
