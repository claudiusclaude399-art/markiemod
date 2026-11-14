# markiemod

Single-player utility client for **Minecraft 1.21.11 on Fabric** (Windows x64).
It is written fully in C++: the menu uses Dear ImGui, the hook uses MinHook, and modules talk to the game through JNI.

It is built for exactly this version. Every name is hardcoded and nothing is meant to survive a game update.

## Use

1. Download the `markiemod` artifact from the latest run in the **Actions** tab. It contains `markiemod.dll` and `injector.exe`.
2. Start Minecraft 1.21.11 with Fabric Loader.
3. Run `injector.exe` with the DLL in the same folder. You can also pass a path: `injector.exe C:\path\markiemod.dll`.
4. **Insert** opens or closes the menu. **End** unloads the client, and everything it changed is restored.

Files go in the game directory (`.minecraft` or the instance folder):
- `markiemod.cfg` holds module states, keybinds and settings. It is saved when the menu closes and on unload.
- `markiemod.log` is the log. It is also shown in the menu's *Client* tab.
- `markiemod-imgui.ini` stores the menu window position.

## Modules

| Category | Module | How it works |
|---|---|---|
| Visual | Fullbright | Sets the gamma option above its 1.0 cap by writing the field directly. Restored on disable. |
| Visual | Zoom | Hold **C** (default). Lowers the FOV option and turns on smooth camera, then restores both on release. |
| Visual | Time Changer | Sets the time of day client-side only. The server's time is unchanged. |
| Visual | Clear Weather | Sets the rain and thunder gradients to 0 client-side. |
| Movement | Auto Sprint | Holds the vanilla sprint key, so vanilla still decides when you can sprint. |
| Movement | Flight | Uses creative-style flight through player abilities. Has speed and "keep flying" settings, plus built-in fall-damage protection. |
| Movement | Speed | Movement speed attribute (multiplier). |
| Movement | Step | Step height attribute. |
| Movement | High Jump | Jump strength attribute. |
| World | No Fall | Sends on-ground packets while you fall and caps fall speed so a single tick can't deal damage. |
| World | Fast Place | Caps the right-click cooldown (vanilla is 4 ticks). |
| World | Fast Break | Caps the delay between broken blocks (vanilla is 5 ticks). |

In the menu, a module's arrow opens its settings, its keybind button (Esc unbinds), and Toggle/Hold mode.
Middle and side mouse buttons can also be bound.

Flight and No Fall depend on single-player behavior: the integrated server always allows flight and accepts the client's on-ground flag.

## How it fits together

```
src/
  dllmain.cpp        worker thread: install hook -> wait for teardown -> remove hook -> unload
  core/hooks.*       MinHook on opengl32!wglSwapBuffers
  core/client.*      per-frame orchestration (runs inside the hook, on the render thread)
  core/input.*       window procedure: menu/unload keys, keybinds, blocks game input while the menu is open
  core/gui.*         ImGui menu + module list HUD
  core/config.*      markiemod.cfg
  jni/jvm.*          JavaVM, Fabric (Knot) class loader, ref registry, exception-safe call helpers
  sdk/mappings.hpp   EVERY Minecraft name used (intermediary, 1.21.11) with the yarn name beside it
  sdk/sdk.*          typed wrappers (Minecraft, ClientPlayer, GameOptions, ...)
  modules/           Module base, manager, one .cpp per module
injector/            LoadLibrary injector (finds the game window by GLFW's window class)
```

The JNI layer follows these rules:

- **One thread.** All JNI calls and all module code run on Minecraft's render thread, inside `wglSwapBuffers`. That thread is also the client thread, so modules change game state the same way vanilla code does, with no races. The window procedure runs on that thread too, but it only records key state.
- **Resolved once.** Every class, field and method is declared once in `sdk.cpp` and resolved at startup through Fabric's Knot class loader. Any name that fails is listed in the log and the *Client* tab, and calls to it become no-ops instead of crashing.
- **No leaks or stray exceptions.** Each frame runs inside a JNI local frame, so no local reference outlives it. After every call, any Java exception is logged and cleared.
- **Clear module lifecycle.** The manager calls `onEnable`, then `onTick` (once per client tick) and `onFrame` (every frame), then `onDisable`. Modules that need a world only run while a world is loaded. When you leave the world they go inactive, because the objects they changed no longer exist.

## Adding a module

1. Create `src/modules/<category>/<name>.cpp` with a `Module` subclass and a `make<Name>()` factory. Copy an existing module as the template.
2. Declare the factory in `src/modules/all.hpp`, register it in `modules::registerAll()` (`manager.cpp`), and add the file to `CMakeLists.txt`.
3. If you need a new Minecraft member, add its intermediary name and descriptor to `sdk/mappings.hpp`, declare it in `sdk.cpp`, and wrap it in `sdk.hpp`. You can look names up in the yarn `1.21.11+build.4` mappings.

## Build

On every push, GitHub Actions builds on `windows-latest` with MSVC and CMake (see `.github/workflows/build.yml`).
ImGui v1.91.9b and MinHook v1.3.4 are fetched by CMake. The JDK is used only for `jni.h`.

To build locally, use Visual Studio 2022 and a JDK 21 with `JAVA_HOME` set:

```
cmake -S . -B build -A x64
cmake --build build --config Release
```
