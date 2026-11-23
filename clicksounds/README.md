# Click Sounds (Fabric, Minecraft 1.21.11)

For people who record or stream without a mic. You record your real mouse and keyboard
**once**, and from then on the mod plays one of those recordings every time you click or press
a key, so viewers still hear you clicking.

- **Mouse:** record 10 clicks. Every mouse button press plays a random one of the 10.
- **Keyboard:** record 10 key presses (W, A, S, D). **Every** key on the keyboard plays a random one
  of those 10.
- It never plays the same take twice in a row, and by default ("Extra variation") each play also gets a
  tiny random pitch and volume change, so it doesn't sound like a loop.
- Holding a key plays the sound once, like a real key does. It doesn't repeat while held.

Client-side only. Requires Fabric Loader and Fabric API.

## How to use

1. In a world, press **K** (rebindable under Controls > Click Sounds) or type `/clicksounds`.
2. Click **Record 10 mouse clicks** and click your mouse 10 times, one click at a time.
   Each box turns green when that click has been captured.
3. Click **Record 10 key presses** and press W, A, S, D 10 times in total.
4. Hit **Test click** / **Test key** to hear them. Set the volume with the sliders.

Esc during recording cancels it, and your old sounds are kept. You only need a microphone for those
few seconds of recording. Afterwards the mod never touches the mic.

The sounds play through your normal speakers/headphones output (not through Minecraft's volume
sliders), so OBS / Discord desktop-audio capture picks them up.

## No mic at all?

Put your own `.wav` files in `config/clicksounds/mouse/` and `config/clicksounds/keyboard/`
(the **Open sounds folder** button opens it) and restart. Any number of files works. A random one
is picked each time.

## Files

- `config/clicksounds/mouse/*.wav`, `config/clicksounds/keyboard/*.wav`: the recordings
- `config/clicksounds.properties`: on/off, volumes, variation

## Notes

- macOS: the launcher (Java) needs Microphone permission (System Settings > Privacy & Security >
  Microphone) for recording.
- The mic used is your system's default input device.

## Build

Push to GitHub. The "build clicksounds" Action builds the jar (artifact `clicksounds-jar`; use the
one without `-sources`). Locally: `gradle build` with Gradle 9.2+ and Java 21, inside `clicksounds/`.
