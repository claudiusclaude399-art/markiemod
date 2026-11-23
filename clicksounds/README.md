# Click Sounds (Fabric, Minecraft 1.21.11)

For people who record or stream without a mic. You record your real mouse and keyboard
**once**, and from then on the mod plays one of those recordings every time you click or press
a key, so viewers still hear you clicking.

- **Mouse:** record 10 clicks. Every mouse button press plays a random one of the 10.
- **Keyboard:** record 10 key presses (W, A, S, D). **Every** key on the keyboard plays a random one
  of those 10.
- **Press and release:** each take captures both the "down" and the "let go" sound. Pressing plays the
  press sound, and releasing plays the release sound *of the same take*, just like a real switch.
- It never plays the same take twice in a row, and by default ("Extra variation") each play also gets a
  tiny random pitch and volume change, so it doesn't sound like a loop.
- Holding a key plays the press once and the release when you let go. Nothing repeats while held.

Client-side only. Requires Fabric Loader and Fabric API.

## How to use

1. Open the menu: press **K** in a world (rebindable under Controls > Click Sounds), type
   `/clicksounds`, or use the Configure button in **Mod Menu** (works from the title screen).
2. Click **Record 10 mouse clicks** and click your mouse 10 times, one click at a time (press and
   let go normally). Each box turns green when that click has been captured.
3. Click **Record 10 key presses** and press W, A, S, D 10 times in total.
4. Open **Mouse takes... / Keyboard takes...** to listen to every take and **Redo** any bad ones
   individually. Dragging a volume slider plays a preview.

Esc during recording cancels it, and your old sounds are kept. You only need a microphone for those
few seconds of recording. Afterwards the mod never touches the mic.

## Settings

| Setting | What it does |
| --- | --- |
| Mouse / Keyboard sounds | On/off per type. There's also an unbound **Toggle click sounds** hotkey in Controls. |
| Mouse / Keyboard volume | 0-200%. |
| Release sounds | Play the "let go" sound too. |
| Extra variation | Tiny random pitch/volume change on every play. |
| Mic | Which microphone records (click to cycle through devices). |
| Output | Where the sounds play. Default is your normal speakers/headphones, which OBS / Discord desktop audio already captures. Pick a virtual audio cable to send the clicks *only* to your stream/recording. |

The sounds are played by the mod itself, not by Minecraft's sound engine, so Minecraft's volume
sliders don't affect them.

## No mic at all?

Put your own `.wav` files in `config/clicksounds/mouse/` and `config/clicksounds/keyboard/`
(the **Open sounds folder** button opens it) and restart. Any number of files works. A random one
is picked each time. To add a release sound for `foo.wav`, name it `foo_up.wav`.

## Files

- `config/clicksounds/mouse/*.wav`, `config/clicksounds/keyboard/*.wav`: the recordings
  (`*_up.wav` = release sound)
- `config/clicksounds.properties`: all settings

## Notes

- macOS: the launcher (Java) needs Microphone permission (System Settings > Privacy & Security >
  Microphone) for recording.

## Build

Push to GitHub. The "build clicksounds" Action builds the jar (artifact `clicksounds-jar`; use the
one without `-sources`). Locally: `gradle build` with Gradle 9.2+ and Java 21, inside `clicksounds/`.
