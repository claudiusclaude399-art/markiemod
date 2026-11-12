// SVG overlay: corner brackets, arrows and eval badges drawn on top of a board.
// The SVG uses an 8x8 viewBox, one unit per square.
(function (g) {
  const NS = 'http://www.w3.org/2000/svg';

  function create() {
    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 8 8');
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;pointer-events:none;overflow:visible';
    return svg;
  }

  function xy(sq, flipped) {
    const f = sq.charCodeAt(0) - 97;
    const r = +sq[1] - 1;
    return { x: flipped ? 7 - f : f, y: flipped ? r : 7 - r };
  }

  function node(tag, attrs, parent) {
    const n = document.createElementNS(NS, tag);
    for (const k in attrs) n.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(n);
    return n;
  }

  function brackets(svg, x, y, color, fill) {
    node('rect', { x, y, width: 1, height: 1, fill: color, 'fill-opacity': fill }, svg);
    const i = 0.07;
    const l = 0.3;
    const w = 0.075;
    const corners = [
      [x + i, y + i, 1, 1],
      [x + 1 - i, y + i, -1, 1],
      [x + i, y + 1 - i, 1, -1],
      [x + 1 - i, y + 1 - i, -1, -1],
    ];
    for (const [cx, cy, dx, dy] of corners) {
      node(
        'path',
        {
          d: `M${cx + dx * l} ${cy} L${cx} ${cy} L${cx} ${cy + dy * l}`,
          stroke: color,
          'stroke-width': w,
          fill: 'none',
          'stroke-linecap': 'square',
        },
        svg
      );
    }
  }

  function arrow(svg, a, b, color) {
    const x1 = a.x + 0.5;
    const y1 = a.y + 0.5;
    const x2 = b.x + 0.5;
    const y2 = b.y + 0.5;
    const dx = x2 - x1;
    const dy = y2 - y1;
    const len = Math.hypot(dx, dy) || 1;
    const ux = dx / len;
    const uy = dy / len;
    const head = 0.42;
    const hw = 0.24;
    const sx = x1 + ux * 0.18;
    const sy = y1 + uy * 0.18;
    const bx = x2 - ux * head;
    const by = y2 - uy * head;
    node(
      'line',
      { x1: sx, y1: sy, x2: bx, y2: by, stroke: color, 'stroke-width': 0.17, 'stroke-opacity': 0.82, 'stroke-linecap': 'round' },
      svg
    );
    node(
      'polygon',
      {
        points: `${x2},${y2} ${bx - uy * hw},${by + ux * hw} ${bx + uy * hw},${by - ux * hw}`,
        fill: color,
        'fill-opacity': 0.82,
      },
      svg
    );
  }

  function badge(svg, x, y, color, text) {
    if (!text) return;
    const w = Math.max(0.34, 0.12 + text.length * 0.11);
    const bx = Math.min(x + 1 - w + 0.12, 8 - w);
    const by = Math.max(y - 0.1, 0);
    node('rect', { x: bx, y: by, width: w, height: 0.3, rx: 0.15, fill: color, stroke: '#fff', 'stroke-width': 0.03 }, svg);
    const t = node(
      'text',
      {
        x: bx + w / 2,
        y: by + 0.155,
        'text-anchor': 'middle',
        'dominant-baseline': 'central',
        'font-size': 0.19,
        'font-weight': 700,
        'font-family': 'system-ui,sans-serif',
        fill: '#fff',
      },
      svg
    );
    t.textContent = text;
  }

  // marks: [{kind:'move', from, to, color, badge}] or [{kind:'square', sq, color, badge}]
  function draw(svg, marks, flipped) {
    while (svg.firstChild) svg.removeChild(svg.firstChild);
    for (const m of marks || []) {
      if (m.kind === 'square') {
        const p = xy(m.sq, flipped);
        brackets(svg, p.x, p.y, m.color, 0.28);
        badge(svg, p.x, p.y, m.color, m.badge);
      } else if (m.kind === 'move') {
        const a = xy(m.from, flipped);
        const b = xy(m.to, flipped);
        brackets(svg, a.x, a.y, m.color, 0.12);
        brackets(svg, b.x, b.y, m.color, 0.3);
        arrow(svg, a, b, m.color);
        badge(svg, b.x, b.y, m.color, m.badge);
      }
    }
  }

  g.CAOverlay = { create, draw };
})(globalThis);
