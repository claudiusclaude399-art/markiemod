# Chess Coach: Move Tiers & Hints (Chrome extension)

A chess study helper. It runs Stockfish 19 (lite, WebAssembly) inside your browser,
so analysis works offline and nothing is sent to a server.

## What it does

- **Move tiers.** Choose how strong a move you want to see:
  - **Brilliant (1 move):** the engine's top move. It is tagged `!!` when it's a sound sacrifice and `!` when it's the only good move.
  - **Excellent (2 moves):** near-best alternatives.
  - **Good (3 moves):** solid, playable moves that are not the top choice.
  - **All:** every candidate line, graded Best / Excellent / Good / Inaccuracy / Mistake / Blunder.
- **Board overlay.** Corner brackets, an arrow and an eval badge on the board, like the video.
  Hover a move card to preview it on the board.
- **Line preview.** Click a move to see the position after it on a mini board, then step or auto-play through the engine's line.
- **Hints.** Press **Hint** again for a bigger clue each time: first which piece, then where it goes, then the full line.
  Turn on **Hint mode** to keep the answers hidden until you ask.
- **Auto** re-analyzes whenever the position changes. **Think** sets the engine time.
- **Offline board.** Set up any position (click to move, or paste a FEN) and analyze it. You can also play the suggested move straight from the panel.

## Where it works

| Page | Coach |
| --- | --- |
| chess.com Analysis board / Game Review (`/analysis…`) | on |
| chess.com games vs computer bots (`/play/computer`, `/game/computer/…`) | on |
| Offline board (extension popup → *Open offline board*) | on |
| chess.com live or daily games against people, rated **or unrated** | **off** |

chess.com's fair-play policy bans engine help in any game against a human, including
unrated and casual games, and accounts get closed for it. The allowlist is in `shared/allow.js`
(and is checked again in the background worker).

## Install (unpacked)

1. Open `chrome://extensions`.
2. Switch on **Developer mode** (top right).
3. Click **Load unpacked** and select this `chess-analyzer-extension` folder.
4. Open chess.com Analysis or a bot game. The Chess Coach panel appears on the right; drag it by its header.
   Or click the extension icon and choose **Open offline board**.

## Files

```
manifest.json          MV3 manifest
background.js          service worker: checks the page, routes analysis requests
offscreen.html/.js     hosts the Stockfish Web Worker
lib/analysis.js        UCI/MultiPV parsing, move grading, sacrifice detection
lib/chess.js           chess.js 1.4.0 (BSD-2-Clause), used for legal moves and SAN
engine/                Stockfish.js 19 lite single-threaded (GPLv3)
shared/panel.js        the coach panel UI (tiers, cards, hints, preview)
shared/overlay.js      SVG brackets/arrows/badges
shared/miniboard.js    small board renderer
shared/allow.js        page allowlist
content/content.js     chess.com integration (reads the board, draws overlay)
content/page-bridge.js reads the FEN from chess.com's board component
board/                 offline board page
popup/                 toolbar popup
```

## How moves are graded

Each line's score is converted to a win chance (lichess curve). A move's grade comes from how
much win chance it gives up compared with the best move:
≤2% Excellent, ≤5% Good, ≤10% Inaccuracy, ≤20% Mistake, more is a Blunder.

## Licenses

Stockfish.js is GPLv3 (`engine/STOCKFISH-LICENSE.txt`). chess.js is BSD-2-Clause (`lib/CHESSJS-LICENSE.txt`).
