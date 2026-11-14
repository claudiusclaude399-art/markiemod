#include "client.hpp"

#include <string>
#include <vector>

#include "../jni/jvm.hpp"
#include "../modules/manager.hpp"
#include "../sdk/sdk.hpp"
#include "config.hpp"
#include "gui.hpp"
#include "input.hpp"
#include "log.hpp"

namespace client {

namespace {
enum class State { Uninitialised, Running, Failed, Detached };
State g_state = State::Uninitialised;
HANDLE g_done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
std::vector<std::string> g_errors;
bool g_jniOk = false;
int g_lastAge = -1;
bool g_lastMenuOpen = false;

void initialise(HDC hdc) {
    logf("==== markiemod starting (Minecraft 1.21.11 / Fabric) ====");
    HWND hwnd = WindowFromDC(hdc);
    if (!hwnd) { logf("[client] ERROR: no window for the GL context"); g_state = State::Failed; return; }

    g_jniOk = jni::init();
    if (g_jniOk) {
        if (!jni::resolveAll(g_errors)) logf("[client] some mappings are missing; affected calls become no-ops");
        g_jniOk = sdk::init();
    } else {
        g_errors.emplace_back("JNI init failed (see log)");
    }

    modules::registerAll();
    config::load();

    if (!gui::init(hwnd) || !input::install(hwnd)) {
        g_state = State::Failed;
        return;
    }
    g_state = State::Running;
    logf("[client] ready - press %s for the menu, %s to unload", input::keyName(input::kMenuKey),
         input::keyName(input::kUnloadKey));
}

Ctx buildCtx() {
    Ctx c;
    c.mc = sdk::Minecraft::get();
    c.options = c.mc.options();
    c.player = c.mc.player();
    c.world = c.mc.world();
    c.inWorld = c.player && c.world;
    c.screenOpen = c.mc.screenOpen();
    c.menuOpen = input::menuOpen();
    int age = c.player ? c.player.age() : -1;
    c.newTick = c.player && age != g_lastAge;
    g_lastAge = age;
    return c;
}

// Keeps the game's cursor/keys consistent with the menu state.
void syncMenu(Ctx& ctx) {
    const bool open = ctx.menuOpen;
    if (open && !g_lastMenuOpen) {
        sdk::KeyBinding::unpressAll();  // keys held when opening would never see their key-up
    }
    if (ctx.inWorld && !ctx.screenOpen) {
        sdk::Mouse mouse = ctx.mc.mouse();
        bool locked = mouse.isCursorLocked();
        if (open && locked) mouse.unlockCursor();          // also re-applied if a screen closes under the menu
        if (!open && g_lastMenuOpen && !locked) mouse.lockCursor();
    }
    if (!open && g_lastMenuOpen) config::save();
    g_lastMenuOpen = open;
}

void teardown() {
    logf("[client] unloading");
    if (g_jniOk) {
        jni::LocalFrame frame;
        Ctx ctx = buildCtx();
        modules::disableAll(ctx);
        if (input::menuOpen() && ctx.inWorld && !ctx.screenOpen) ctx.mc.mouse().lockCursor();
    }
    config::save();
    input::uninstall();
    gui::shutdown();
    if (g_jniOk) {
        sdk::shutdown();
        jni::shutdown();
    }
    g_state = State::Detached;
    logf("[client] unloaded");
    logClose();
    SetEvent(g_done);
}
}  // namespace

HANDLE teardownDoneEvent() { return g_done; }

void onFrame(HDC hdc) {
    if (g_state == State::Detached) return;
    if (g_state == State::Uninitialised) initialise(hdc);
    if (g_state == State::Failed) {
        // Could not hook the window or start ImGui: nothing to show, just detach.
        if (g_jniOk) { sdk::shutdown(); jni::shutdown(); }
        input::uninstall();
        gui::shutdown();
        g_state = State::Detached;
        logf("[client] start-up failed, detaching");
        SetEvent(g_done);
        return;
    }

    if (input::unloadRequested()) { teardown(); return; }

    if (!g_jniOk) {
        gui::render(nullptr, g_errors);
        return;
    }

    jni::LocalFrame frame;  // every local ref created this frame dies here
    Ctx ctx = buildCtx();
    input::setPlaying(ctx.inWorld && !ctx.screenOpen);
    syncMenu(ctx);
    modules::update(ctx);
    gui::render(&ctx, g_errors);
}

}  // namespace client
