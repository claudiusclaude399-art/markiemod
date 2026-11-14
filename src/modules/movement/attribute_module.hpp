#pragma once
#include "../all.hpp"

// Shared logic for modules that just set a player attribute's base value.
// Re-applied every tick, so it survives respawns, dimension changes and server
// attribute syncs; on disable the vanilla player base value is restored.
class AttributeModule : public Module {
public:
    AttributeModule(const char* name, const char* desc, sdk::Attribute attr,
                    const char* settingName, float def, float min, float max, bool multiplier)
        : Module(name, desc, Category::Movement), attr_(attr), multiplier_(multiplier),
          value_(addFloat(settingName, def, min, max, "%.2f")) {}

    void onEnable(Ctx& ctx) override { apply(ctx); }
    void onTick(Ctx& ctx) override { apply(ctx); }
    void onDisable(Ctx& ctx) override { ctx.player.attribute(attr_).setBase(sdk::defaultBase(attr_)); }

private:
    void apply(Ctx& ctx) {
        double v = multiplier_ ? sdk::defaultBase(attr_) * value_.f : value_.f;
        auto inst = ctx.player.attribute(attr_);
        if (inst && inst.base() != v) inst.setBase(v);
    }

    sdk::Attribute attr_;
    bool multiplier_;
    Setting& value_;
};
