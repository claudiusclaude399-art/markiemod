// Where the coach is allowed to run on chess.com.
//
// Only pages where engine help is permitted are on this list:
//   - the Analysis board / Game Review (/analysis...)
//   - games against chess.com's computer bots (/play/computer, /game/computer/...)
//
// Live and daily games against people (/game/live, /game/daily, /play/online, ...)
// are deliberately NOT on the list. chess.com's fair-play policy forbids engine
// assistance in any game against a human, rated or unrated.
(function (g) {
  const RE = /^\/(?:[a-z]{2}(?:-[a-z]{2,4})?\/)?(analysis(?:\/|$)|play\/computer(?:\/|$)|game\/computer\/)/i;

  function modeForUrl(url) {
    try {
      const u = new URL(url);
      if (u.hostname !== 'www.chess.com' && u.hostname !== 'chess.com') return null;
      const m = RE.exec(u.pathname);
      if (!m) return null;
      return m[1].toLowerCase().startsWith('analysis') ? 'analysis' : 'bot';
    } catch (_) {
      return null;
    }
  }

  g.CAAllow = { modeForUrl };
})(globalThis);
