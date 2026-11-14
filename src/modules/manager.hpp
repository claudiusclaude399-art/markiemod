#pragma once
#include <memory>
#include <vector>

#include "module.hpp"

namespace modules {

void registerAll();                       // creates every module (call once)
std::vector<std::unique_ptr<Module>>& all();
Module* find(const std::string& name);

// Per frame, render thread. Applies enable/disable transitions and runs hooks.
void update(Ctx& ctx);

// Win32 key press, only forwarded while playing (no screen, menu closed).
// Toggles bound Toggle-mode modules; the change is applied on the next update().
void onKeyPressed(int vk);

// Restore everything the modules changed. Used on unload.
void disableAll(Ctx& ctx);

}  // namespace modules
