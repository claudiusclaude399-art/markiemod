// markiemod - single-player utility client for Minecraft 1.21.11 (Fabric).
//
// Life cycle:
//   inject -> worker thread installs the wglSwapBuffers hook
//          -> first frame (render thread): JNI + ImGui + window hook start up
//          -> every frame: modules run, menu/HUD drawn
//   End key -> render thread restores everything, signals the worker
//          -> worker removes the hook and unloads the DLL.
#include <windows.h>

#include "core/client.hpp"
#include "core/hooks.hpp"
#include "core/log.hpp"

namespace {
HMODULE g_module = nullptr;

DWORD WINAPI worker(LPVOID) {
    if (!hooks::install()) {
        logf("[main] hook install failed, unloading");
        logClose();
        FreeLibraryAndExitThread(g_module, 0);
    }
    WaitForSingleObject(client::teardownDoneEvent(), INFINITE);
    hooks::uninstall();
    FreeLibraryAndExitThread(g_module, 0);
    return 0;
}
}  // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = module;
        DisableThreadLibraryCalls(module);
        HANDLE t = CreateThread(nullptr, 0, worker, nullptr, 0, nullptr);
        if (t) CloseHandle(t);
    }
    return TRUE;
}
