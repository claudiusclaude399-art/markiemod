#include "input.hpp"

#include <atomic>
#include <cstdio>

#include "../modules/manager.hpp"
#include "imgui.h"
#include "log.hpp"

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam);

namespace input {

namespace {
HWND g_hwnd = nullptr;
WNDPROC g_original = nullptr;
bool g_down[256] = {};
bool g_menuOpen = false;
bool g_playing = false;
bool g_capturing = false;
bool g_captured = false;
int g_capturedKey = 0;
std::atomic<bool> g_unload{false};

bool isKeyboardMessage(UINT m) {
    return m == WM_KEYDOWN || m == WM_KEYUP || m == WM_SYSKEYDOWN || m == WM_SYSKEYUP || m == WM_CHAR ||
           m == WM_SYSCHAR || m == WM_DEADCHAR || m == WM_UNICHAR;
}
bool isMouseMessage(UINT m) {
    return m >= WM_MOUSEFIRST && m <= WM_MOUSELAST;
}

int mouseVk(UINT msg, WPARAM wp) {
    switch (msg) {
        case WM_LBUTTONDOWN: case WM_LBUTTONUP: return VK_LBUTTON;
        case WM_RBUTTONDOWN: case WM_RBUTTONUP: return VK_RBUTTON;
        case WM_MBUTTONDOWN: case WM_MBUTTONUP: return VK_MBUTTON;
        case WM_XBUTTONDOWN: case WM_XBUTTONUP: return GET_XBUTTON_WPARAM(wp) == XBUTTON1 ? VK_XBUTTON1 : VK_XBUTTON2;
        default: return 0;
    }
}

// Returns true if the press was consumed (menu key, capture, ...).
bool onPress(int vk) {
    if (g_capturing) {
        g_capturedKey = (vk == VK_ESCAPE) ? 0 : vk;
        g_captured = true;
        g_capturing = false;
        return true;
    }
    if (vk == kMenuKey) { g_menuOpen = !g_menuOpen; return true; }
    if (vk == kUnloadKey) { g_unload = true; return true; }
    if (g_menuOpen && vk == VK_ESCAPE) { g_menuOpen = false; return true; }
    if (!g_menuOpen && g_playing) modules::onKeyPressed(vk);
    return false;
}

LRESULT CALLBACK hookedWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN: {
            int vk = static_cast<int>(wp) & 0xFF;
            bool repeat = (lp & (1 << 30)) != 0;
            g_down[vk] = true;
            if (!repeat && onPress(vk)) return 0;
            break;
        }
        case WM_KEYUP:
        case WM_SYSKEYUP:
            g_down[static_cast<int>(wp) & 0xFF] = false;
            break;
        case WM_LBUTTONDOWN: case WM_RBUTTONDOWN: case WM_MBUTTONDOWN: case WM_XBUTTONDOWN: {
            int vk = mouseVk(msg, wp);
            g_down[vk] = true;
            // Only side/middle buttons are bindable; left/right stay with the game/menu.
            if (g_capturing && (vk == VK_MBUTTON || vk == VK_XBUTTON1 || vk == VK_XBUTTON2)) { onPress(vk); return 0; }
            if (!g_menuOpen && g_playing && (vk == VK_MBUTTON || vk == VK_XBUTTON1 || vk == VK_XBUTTON2))
                modules::onKeyPressed(vk);
            break;
        }
        case WM_LBUTTONUP: case WM_RBUTTONUP: case WM_MBUTTONUP: case WM_XBUTTONUP:
            g_down[mouseVk(msg, wp)] = false;
            break;
        case WM_KILLFOCUS:
            for (bool& d : g_down) d = false;  // key-ups are lost while unfocused
            break;
        default:
            break;
    }

    if (g_menuOpen) {
        if (ImGui::GetCurrentContext()) ImGui_ImplWin32_WndProcHandler(hwnd, msg, wp, lp);
        // The game must not see input while the menu is open.
        if (msg == WM_INPUT) return DefWindowProcW(hwnd, msg, wp, lp);  // raw input needs DefWindowProc cleanup
        if (isKeyboardMessage(msg) || isMouseMessage(msg)) return 0;
        if (msg == WM_SETCURSOR && LOWORD(lp) == HTCLIENT) return TRUE;  // ImGui sets the cursor
    }
    return CallWindowProcW(g_original, hwnd, msg, wp, lp);
}
}  // namespace

bool install(HWND window) {
    g_hwnd = window;
    g_original = reinterpret_cast<WNDPROC>(
        SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&hookedWndProc)));
    if (!g_original) { logf("[input] ERROR: SetWindowLongPtr failed (%lu)", GetLastError()); return false; }
    return true;
}

void uninstall() {
    if (g_hwnd && g_original) SetWindowLongPtrW(g_hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(g_original));
    g_original = nullptr;
    g_hwnd = nullptr;
}

bool isDown(int vk) { return vk > 0 && vk < 256 && g_down[vk]; }
bool menuOpen() { return g_menuOpen; }
void setMenuOpen(bool open) { g_menuOpen = open; }
void setPlaying(bool playing) { g_playing = playing; }

void beginCapture() { g_capturing = true; g_captured = false; }
bool capturing() { return g_capturing; }
bool takeCaptured(int& vk) {
    if (!g_captured) return false;
    g_captured = false;
    vk = g_capturedKey;
    return true;
}

bool unloadRequested() { return g_unload; }
void requestUnload() { g_unload = true; }

const char* keyName(int vk) {
    static char buf[64];
    switch (vk) {
        case 0: return "None";
        case VK_MBUTTON: return "Mouse 3";
        case VK_XBUTTON1: return "Mouse 4";
        case VK_XBUTTON2: return "Mouse 5";
        default: break;
    }
    UINT scan = MapVirtualKeyW(static_cast<UINT>(vk), MAPVK_VK_TO_VSC);
    LONG lparam = static_cast<LONG>(scan << 16);
    switch (vk) {  // extended keys need bit 24 for the right name
        case VK_INSERT: case VK_DELETE: case VK_HOME: case VK_END: case VK_PRIOR: case VK_NEXT:
        case VK_LEFT: case VK_RIGHT: case VK_UP: case VK_DOWN: case VK_RCONTROL: case VK_RMENU: case VK_DIVIDE:
            lparam |= 1 << 24;
            break;
        default: break;
    }
    if (GetKeyNameTextA(lparam, buf, sizeof(buf)) > 0) return buf;
    snprintf(buf, sizeof(buf), "Key %d", vk);
    return buf;
}

}  // namespace input
