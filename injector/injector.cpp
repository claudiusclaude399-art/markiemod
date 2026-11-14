// Minimal LoadLibrary injector for markiemod.
//   injector.exe                 -> injects markiemod.dll (next to this exe) into the running game
//   injector.exe C:\path\x.dll   -> injects that DLL
// The game window is found by GLFW's window class, so the launcher's own
// processes are never picked by mistake.
#include <windows.h>

#include <cstdio>
#include <string>

namespace {

DWORD findGamePid() {
    HWND w = nullptr;
    while ((w = FindWindowExW(nullptr, w, L"GLFW30", nullptr)) != nullptr) {
        wchar_t title[256] = {};
        GetWindowTextW(w, title, 256);
        if (wcsstr(title, L"Minecraft")) {
            DWORD pid = 0;
            GetWindowThreadProcessId(w, &pid);
            return pid;
        }
    }
    return 0;
}

std::wstring defaultDllPath() {
    wchar_t exe[MAX_PATH] = {};
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    std::wstring p(exe);
    size_t slash = p.find_last_of(L"\\/");
    return (slash == std::wstring::npos ? L"" : p.substr(0, slash + 1)) + L"markiemod.dll";
}

int fail(const char* what) {
    std::printf("error: %s (code %lu)\n", what, GetLastError());
    std::printf("press enter to exit\n");
    (void)std::getchar();
    return 1;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    std::wstring dll = argc > 1 ? argv[1] : defaultDllPath();
    wchar_t full[MAX_PATH] = {};
    if (!GetFullPathNameW(dll.c_str(), MAX_PATH, full, nullptr) || GetFileAttributesW(full) == INVALID_FILE_ATTRIBUTES)
        return fail("DLL not found");

    DWORD pid = findGamePid();
    if (!pid) return fail("Minecraft window not found - start the game first");
    std::printf("Minecraft pid %lu\n", pid);

    HANDLE proc = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION |
                                  PROCESS_VM_WRITE | PROCESS_VM_READ,
                              FALSE, pid);
    if (!proc) return fail("OpenProcess failed");

    const SIZE_T bytes = (wcslen(full) + 1) * sizeof(wchar_t);
    void* remote = VirtualAllocEx(proc, nullptr, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote) { CloseHandle(proc); return fail("VirtualAllocEx failed"); }
    if (!WriteProcessMemory(proc, remote, full, bytes, nullptr)) {
        VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
        CloseHandle(proc);
        return fail("WriteProcessMemory failed");
    }

    auto loadLibrary = reinterpret_cast<LPTHREAD_START_ROUTINE>(
        GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW"));
    HANDLE thread = CreateRemoteThread(proc, nullptr, 0, loadLibrary, remote, 0, nullptr);
    if (!thread) {
        VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
        CloseHandle(proc);
        return fail("CreateRemoteThread failed");
    }
    WaitForSingleObject(thread, 10000);
    DWORD loaded = 0;
    GetExitCodeThread(thread, &loaded);  // low 32 bits of the module handle; 0 = failed
    CloseHandle(thread);
    VirtualFreeEx(proc, remote, 0, MEM_RELEASE);
    CloseHandle(proc);

    if (!loaded) return fail("LoadLibraryW failed inside the game");
    std::printf("injected %ls\n", full);
    return 0;
}
