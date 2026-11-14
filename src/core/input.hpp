#pragma once
#include <windows.h>

// Window-procedure hook on the game window. Runs on the render thread (GLFW
// pumps messages there), but does NO JNI: it only records state that the next
// frame acts on.
namespace input {

inline constexpr int kMenuKey = VK_INSERT;  // open/close the menu
inline constexpr int kUnloadKey = VK_END;   // unload the client

bool install(HWND window);
void uninstall();

bool isDown(int vk);  // physical key / mouse button state, cleared on focus loss

bool menuOpen();
void setMenuOpen(bool open);

// Set every frame: true while in a world with no screen and the menu closed.
// Module keybinds only fire while this is true.
void setPlaying(bool playing);

// Keybind capture for the menu: begin, then poll. Esc = unbind (returns 0).
void beginCapture();
bool capturing();
bool takeCaptured(int& vk);

bool unloadRequested();
void requestUnload();

const char* keyName(int vk);  // "None" for 0

}  // namespace input
