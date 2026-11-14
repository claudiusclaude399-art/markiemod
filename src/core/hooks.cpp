#include "hooks.hpp"

#include <windows.h>

#include <atomic>

#include "MinHook.h"
#include "client.hpp"
#include "log.hpp"

namespace hooks {

namespace {
using SwapBuffersFn = BOOL(WINAPI*)(HDC);
SwapBuffersFn g_original = nullptr;
void* g_target = nullptr;
std::atomic<int> g_inFlight{0};

BOOL WINAPI swapBuffersDetour(HDC hdc) {
    ++g_inFlight;
    client::onFrame(hdc);
    BOOL r = g_original(hdc);
    --g_inFlight;
    return r;
}
}  // namespace

bool install() {
    HMODULE gl = GetModuleHandleW(L"opengl32.dll");
    if (!gl) { logf("[hooks] ERROR: opengl32.dll not loaded"); return false; }
    g_target = reinterpret_cast<void*>(GetProcAddress(gl, "wglSwapBuffers"));
    if (!g_target) { logf("[hooks] ERROR: wglSwapBuffers not found"); return false; }

    if (MH_Initialize() != MH_OK) { logf("[hooks] ERROR: MH_Initialize failed"); return false; }
    if (MH_CreateHook(g_target, reinterpret_cast<void*>(&swapBuffersDetour),
                      reinterpret_cast<void**>(&g_original)) != MH_OK) {
        logf("[hooks] ERROR: MH_CreateHook failed");
        MH_Uninitialize();
        return false;
    }
    if (MH_EnableHook(g_target) != MH_OK) {
        logf("[hooks] ERROR: MH_EnableHook failed");
        MH_Uninitialize();
        return false;
    }
    logf("[hooks] wglSwapBuffers hooked");
    return true;
}

void uninstall() {
    if (g_target) MH_DisableHook(g_target);
    // A frame may still be inside the detour (in the original SwapBuffers).
    for (int i = 0; i < 2000 && g_inFlight.load() > 0; ++i) Sleep(5);  // up to 10 s for a stalled frame
    MH_Uninitialize();
    Sleep(50);  // let the last frame return out of our code
    g_target = nullptr;
}

}  // namespace hooks
