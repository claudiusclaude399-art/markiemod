#pragma once
#include <windows.h>

#include <string>
#include <vector>

struct Ctx;

namespace gui {

bool init(HWND window);  // ImGui context + Win32/OpenGL3 backends (render thread)
void shutdown();

// Draws the menu (if open) and the HUD, then renders ImGui into the default
// framebuffer. `ctx` is null when the JNI layer failed to start; `errors`
// is shown at the top of the menu.
void render(Ctx* ctx, const std::vector<std::string>& errors);

}  // namespace gui
