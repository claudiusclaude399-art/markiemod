#include "../all.hpp"

// Holds the vanilla sprint key, so vanilla decides when sprinting is allowed
// (moving forward, enough hunger, not sneaking, ...). The `pressed` field is
// written directly because setPressed() toggles when sprint is in toggle mode.
namespace {
class AutoSprint : public Module {
public:
    AutoSprint() : Module("Auto Sprint", "Always sprint when moving forward", Category::Movement) {}
    void onFrame(Ctx& ctx) override { ctx.options.sprintKey().setPressedRaw(true); }
    void onDisable(Ctx& ctx) override { ctx.options.sprintKey().setPressedRaw(false); }
};
}  // namespace

std::unique_ptr<Module> makeAutoSprint() { return std::make_unique<AutoSprint>(); }
