#pragma once
#include <memory>
#include <string>
#include <vector>

#include "../sdk/sdk.hpp"

// What a module sees every frame. Built once per frame on the render thread.
struct Ctx {
    sdk::Minecraft mc;
    sdk::GameOptions options;
    sdk::ClientPlayer player;   // may be null (main menu)
    sdk::ClientWorld world;     // may be null (main menu)
    bool inWorld = false;       // player && world
    bool screenOpen = false;    // a Minecraft screen (inventory, chat, pause...) is open
    bool menuOpen = false;      // our ImGui menu is open
    bool newTick = false;       // a client tick happened since the previous frame
};

enum class Category { Visual, Movement, World };
const char* categoryName(Category c);

struct Setting {
    enum class Type { Bool, Float, Int };
    std::string name;
    Type type;
    bool b = false;
    float f = 0.f, fMin = 0.f, fMax = 1.f;
    int i = 0, iMin = 0, iMax = 1;
    std::string format;  // ImGui format for sliders
};

enum class BindMode { Toggle, Hold };

class Module {
public:
    Module(std::string name, std::string description, Category category, bool needsWorld = true);
    virtual ~Module() = default;

    // ---- hooks (render thread) -----------------------------------------------
    // onEnable/onDisable bracket the module being applied. A module that
    // `needsWorld` is only applied while in a world; if the world goes away
    // the module simply becomes inactive (the objects it changed are gone too).
    virtual void onEnable(Ctx&) {}
    virtual void onDisable(Ctx&) {}
    virtual void onTick(Ctx&) {}   // once per client tick (20/s, paused when the game pauses)
    virtual void onFrame(Ctx&) {}  // every frame

    // ---- state ------------------------------------------------------------------
    const std::string& name() const { return name_; }
    const std::string& description() const { return description_; }
    Category category() const { return category_; }
    bool needsWorld() const { return needsWorld_; }

    bool enabled = false;  // what the user wants
    bool active = false;   // whether onEnable has run (managed by ModuleManager)
    int key = 0;           // Win32 virtual-key code, 0 = unbound
    BindMode bindMode = BindMode::Toggle;

    std::vector<std::unique_ptr<Setting>>& settings() { return settings_; }

protected:
    Setting& addBool(const std::string& name, bool def);
    Setting& addFloat(const std::string& name, float def, float min, float max, const char* fmt = "%.2f");
    Setting& addInt(const std::string& name, int def, int min, int max);

private:
    std::string name_, description_;
    Category category_;
    bool needsWorld_;
    std::vector<std::unique_ptr<Setting>> settings_;
};
