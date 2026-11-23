# Click Sounds (Fabric, Minecraft 1.21.11)

For people who record or stream without a mic. You record your real mouse and keyboard **once**
(or use a downloaded clickpack), and from then on the mod plays one of those sounds every time you
click or press a key, so viewers still hear you clicking.

- **Clickpacks:** sounds are organised in packs with their own folders for clicks, releases,
  soft clicks and hard clicks. Downloaded packs (.wav/.ogg, including Geometry Dash clickbot packs)
  can be dropped straight in.
- **Mouse:** record 10 clicks. Every mouse button press plays a random one of the 10. Or give
  Left / Right / Middle / side buttons their **own** sounds, and record **scroll wheel** notches too.
- **Keyboard:** record 10 key presses (W, A, S, D). **Every** key on the keyboard plays a random one
  of those 10. Or record W, A, S, D (or any key) **individually** so each key has its own sound.
- **Desk stereo:** like a real desk, the keyboard sounds come from the left (every key from its own
  spot, A further left than L) and the mouse from the right.
- **Press and release:** each take captures both the "down" and the "let go" sound. Pressing plays the
  press sound, and releasing plays the release sound *of the same take*, just like a real switch.
- It never plays the same take twice in a row, and by default ("Extra variation") each play also gets a
  tiny random pitch and volume change, so it doesn't sound like a loop.
- Holding a key plays the press once and the release when you let go. Nothing repeats while held.

Client-side only. Requires Fabric Loader and Fabric API.

## How to use

1. Open the menu: press **K** in a world (rebindable under Controls > Click Sounds), type
   `/clicksounds`, or use the Configure button in **Mod Menu** (works from the title screen).
2. Open **Mouse clickpack...**, stay on the **Clicks** tab and hit **Record 10**. Click your mouse 10
   times, one click at a time (press and let go normally).
3. Do the same in **Keyboard clickpack...** pressing W, A, S, D.
4. Optional: record the **Soft clicks** and **Hard clicks** tabs too (see below).

When a recording is done you land on its tab: every take is shown as a **sound wave** (click in
blue, release in orange). Click a wave to hear it (a playhead runs across it), **Play all** plays
them back to back, and **Redo** re-records just that one.

While recording you see a **live sound wave** from your mic. Each press and release is marked, every
click it captured is highlighted green, and each finished take appears as a mini wave. Esc cancels
and keeps your old sounds. You only need a microphone for those few seconds of recording.

**Previews:** the ▶ button next to "clickpack..." plays a random click exactly as it sounds in game.
Dragging a volume slider plays previews too, and a redone take plays straight away.

## Clickpacks

Every set of sounds is a **clickpack**: a folder in `config/clicksounds/packs/`. Your recordings go
in the pack called `My clicks`. The mouse and the keyboard each use their own pack, so you can e.g.
use a downloaded pack for the mouse and your own recording for the keyboard.

### Switching clickpacks

Mouse and keyboard work the same way, and each can be switched on its own:

- **Pack picker:** click **Mouse pack: ...** or **Keys pack: ...** in the menu. It lists every pack with
  what it holds for that device (e.g. "12 clicks, 4 soft, 3 hard"); press ▶ to hear a pack without
  switching, click its name to use it. Mouse / Keyboard tabs at the top, plus **Use for keyboard too**
  (or mouse) to give both the same pack.
- **Hotkeys:** **Next mouse clickpack** and **Next keyboard clickpack** (Controls > Click Sounds,
  unbound by default) switch to the next pack mid-game and show its name on screen.
- **Command:** `/clicksounds pack mouse <name>` or `/clicksounds pack keyboard <name>` (Tab completes
  pack names). `/clicksounds pack mouse` on its own shows the current pack and all packs.

```
packs/<Pack name>/
  mouse/
    clicks/         normal clicks            (needed)
    releases/       normal releases
    softclicks/     soft clicks              (optional)
    softreleases/   soft releases            (optional)
    hardclicks/     hard clicks              (optional)
    hardreleases/   hard releases            (optional)
  keyboard/
    clicks/  releases/  softclicks/  softreleases/  hardclicks/  hardreleases/
```

- A release goes with the click of the **same file name** (`clicks/03.wav` and `releases/03.wav`).
  Clicks without a matching release get a random release from that genre's folder (or from
  `releases/` if the genre has none).
- **.wav** and **.ogg** files are both fine, with any name, sample rate, mono or stereo.
- Packs made for Geometry Dash clickbots (the genre folders directly in the pack folder, or in
  `player1/`) work as **mouse** packs as they are. Just drop the folder into `packs/` and hit
  **Reload packs**.
- **Downloaded a .zip?** Drop it into `packs/` as it is. It's unpacked into a pack of the same
  name the next time the pack picker opens (or on **Reload packs** / restart), and the zip is moved to
  `packs/.imported/`. Only the .wav / .ogg files are taken, and a wrapping folder inside the zip is
  skipped.
- **New pack**, **Rename...** and **Share as .zip** are in the pack picker. Sharing saves
  `config/clicksounds/exported/<pack>.zip`, which anyone can drop into their own packs folder.

### Recording keys individually (W, A, S, D or any key)

In **Keyboard clickpack...** the row at the top picks whose sounds you're looking at / recording:
**All keys | W | A | S | D | Other key...** (Other key: press any key, e.g. Space or Shift).

- Pick **W** and hit **Record 10**: only W presses are recorded (other keys are ignored), and from
  then on W plays its own sounds. Each key has its own Clicks / Soft / Hard tabs and waves.
- Keys without their own sounds play the **All keys** sounds.
- If you only recorded keys individually (no All keys sounds), every other key plays a random
  take from all of them, so recording W, A, S and D is enough for the whole keyboard.

Each key's sounds go in its own folder inside the pack, laid out like the rest:

```
keyboard/
  clicks/  releases/ ...        All keys (shared)
  keys/
    w/      clicks/  releases/  softclicks/  softreleases/  hardclicks/  hardreleases/
    a/      ...
    space/  ...
```

Folder names are Minecraft's key names: letters and digits as themselves, plus `space`,
`left.shift`, `enter`, `tab`, `left.control` and so on (they don't depend on your keyboard layout).

### Mouse buttons and the scroll wheel

**Mouse clickpack...** has the same kind of row: **All | Left | Right | Middle | Back | Fwd | Scroll**.

- Pick **Right** and **Record 10** to give right-clicks their own sound (only right clicks are
  recorded there). Buttons without their own sounds use the **All** sounds.
- **Scroll**: record 10 wheel notches (scroll one notch at a time). In game every notch plays one of
  them (touchpad scrolling is added up into notches). Packs without scroll sounds stay silent when
  you scroll; it never plays click sounds for the wheel.

```
mouse/
  clicks/ releases/ ...          All buttons (shared)
  buttons/
    left/  right/  middle/  back/  forward/     clicks/ releases/ softclicks/ ...
  scroll/
    clicks/  softclicks/  hardclicks/           (no releases: a notch has none)
```

### Desk stereo

With **Desk stereo** on (Sound options), sounds are placed like on a real desk: the keyboard spreads
from the far left (Esc, Q, A, Shift) to just left of the middle (Enter, arrow keys), and the mouse is
on the right. **Desk width** sets how wide that is; dragging it plays a key and then a click so you
can hear it. Turn it off for everything in the centre.

### Soft and hard clicks (optional)

How fast you click decides which genre plays:

| Time since your previous click | Plays |
| --- | --- |
| shorter than 120 ms (spam clicking) | **soft clicks** |
| in between | normal **clicks** |
| longer than 800 ms (first click after a pause) | **hard clicks** |

Both thresholds can be changed, and soft and hard clicks can each be switched off, in
**Sound options...**. A pack without soft or hard clicks just uses its normal clicks. When recording
them, click lightly for soft clicks and firmly for hard clicks.

## Settings

| Setting | What it does |
| --- | --- |
| Mouse pack / Keys pack | Opens the pack picker for that device. |
| Mouse / Keyboard sounds | On/off per type. There's also an unbound **Toggle click sounds** hotkey in Controls. |
| Mouse / Keyboard volume | 0-200%. One loudness level per pack, so soft clicks stay softer than hard ones. |
| Sound options: Release sounds | Play the release sound when you let go. |
| Sound options: Extra variation | Tiny random pitch/volume change on every play. |
| Sound options: Soft / Hard clicks | On/off, and their timing. |
| Sound options: Desk stereo / Desk width | Keyboard on the left, mouse on the right, and how far apart. |
| Sound options: Scroll sounds | A sound per scroll wheel notch (if the pack has scroll sounds). |
| Sound options: Sounds in menus | Also play sounds while a menu or the chat is open. |
| Mic | Which microphone records (click to cycle through devices). |
| Output | Where the sounds play. Default is your normal speakers/headphones, which OBS / Discord desktop audio already captures. Pick a virtual audio cable to send the clicks *only* to your stream/recording. |

The sounds are played by the mod itself, not by Minecraft's sound engine, so Minecraft's volume
sliders don't affect them.

Recordings from older versions (`config/clicksounds/mouse/`, `keyboard/`) are moved into the
`My clicks` pack automatically on first start. Settings are in `config/clicksounds.properties`.

## Notes

- macOS: the launcher (Java) needs Microphone permission (System Settings > Privacy & Security >
  Microphone) for recording.

## Build

Push to GitHub. The "build clicksounds" Action builds the jar (artifact `clicksounds-jar`; use the
one without `-sources`). Locally: `gradle build` with Gradle 9.2+ and Java 21, inside `clicksounds/`.
