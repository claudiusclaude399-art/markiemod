#include "manager.hpp"

#include "../core/input.hpp"
#include "../core/log.hpp"
#include "all.hpp"

namespace modules {

namespace {
std::vector<std::unique_ptr<Module>> g_modules;

bool canRun(const Module& m, const Ctx& ctx) { return ctx.inWorld || !m.needsWorld(); }
}  // namespace

void registerAll() {
    if (!g_modules.empty()) return;
    g_modules.push_back(makeFullbright());
    g_modules.push_back(makeZoom());
    g_modules.push_back(makeTimeChanger());
    g_modules.push_back(makeClearWeather());

    g_modules.push_back(makeAutoSprint());
    g_modules.push_back(makeFlight());
    g_modules.push_back(makeSpeed());
    g_modules.push_back(makeStep());
    g_modules.push_back(makeHighJump());

    g_modules.push_back(makeNoFall());
    g_modules.push_back(makeFastPlace());
    g_modules.push_back(makeFastBreak());
}

std::vector<std::unique_ptr<Module>>& all() { return g_modules; }

Module* find(const std::string& name) {
    for (auto& m : g_modules)
        if (m->name() == name) return m.get();
    return nullptr;
}

void update(Ctx& ctx) {
    const bool playing = ctx.inWorld && !ctx.screenOpen && !ctx.menuOpen;
    for (auto& mp : g_modules) {
        Module& m = *mp;

        // Hold-mode binds follow the physical key, but only while actually playing.
        if (m.bindMode == BindMode::Hold && m.key != 0)
            m.enabled = playing && input::isDown(m.key);

        const bool runnable = canRun(m, ctx);

        if (m.active && !runnable) {
            // World left: the player/world objects it modified no longer exist,
            // so there is nothing to restore.
            m.active = false;
        } else if (m.active && !m.enabled) {
            m.onDisable(ctx);
            m.active = false;
        }

        if (!m.active && m.enabled && runnable) {
            m.onEnable(ctx);
            m.active = true;
        }

        if (m.active) {
            m.onFrame(ctx);
            if (ctx.newTick) m.onTick(ctx);
        }
    }
}

void onKeyPressed(int vk) {
    for (auto& m : g_modules) {
        if (m->key == vk && m->bindMode == BindMode::Toggle) {
            m->enabled = !m->enabled;
            logf("[module] %s %s", m->name().c_str(), m->enabled ? "enabled" : "disabled");
        }
    }
}

void disableAll(Ctx& ctx) {
    for (auto& m : g_modules) {
        if (m->active && canRun(*m, ctx)) m->onDisable(ctx);
        m->active = false;
    }
}

}  // namespace modules
