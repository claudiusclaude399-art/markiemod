# MacroForge Bridge (Fabric 1.21.11, client-side)

This mod connects Minecraft to the MacroForge app on the same PC.

## Build
- **GitHub (recommended):** push this folder to a GitHub repo, then open the **Actions** tab. When the "Build MacroForge Bridge" run finishes, download the `macroforge-bridge` artifact. It contains the jar.
- **Locally:** run `gradle build` with Java 21. The jar ends up in `build/libs/`.

## Install
1. Install Fabric Loader for 1.21.11, plus Fabric API.
2. Put `macroforge-bridge-1.0.0.jar` in `.minecraft\mods`.
3. Start Minecraft and MacroForge. The MacroForge "Minecraft" page will show **Connected**.

## How it connects
- **Port:** the mod listens on 127.0.0.1 only, starting at port 25599.
- **Token:** it writes its port and a random token to `%APPDATA%\MacroForge\minecraft-bridge.json`. Only programs that can read that file can connect.
- **Protocol:** newline-delimited JSON.
  - The mod sends `state` (about 5 times a second, including crosshair target, nearby players and mobs, effects and attack cooldown), `event` (chat, damage, death, respawn, totem_pop, join, leave, screen_open/close, slot, held_item, level_up, hunger, actionbar, crosshair_entity, crosshair_block, crosshair_none, attack, target_shield_up, target_shield_down, start_falling, landed, jump, start_gliding, stop_gliding, enter_water, leave_water) and `result` messages. Movement state covers falling, rising, in_air, gliding, flying, swimming, in_lava, climbing, riding, sleeping, fall_distance, height_above_ground, speed, velocity and standing_on. The state includes the target's shield, use item, held items, armour and hurt flag, plus hit and combo counters.
  - It accepts `cmd` messages with these ops: `chat`, `key`, `slot`, `select_item`, `look`, `look_at`, `message`, `close_screen`, `respawn`, `release_all`, `state` and `keys`.
