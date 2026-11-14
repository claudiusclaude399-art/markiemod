#include "../all.hpp"

// Caps ClientPlayerInteractionManager.blockBreakingCooldown (vanilla: 5 ticks
// between breaking one block and starting the next). Mining speed itself is
// unchanged, because the server checks it.
namespace {
class FastBreak : public Module {
public:
    FastBreak() : Module("Fast Break", "Remove the delay between broken blocks", Category::World) {}
    void onFrame(Ctx& ctx) override {
        auto im = ctx.mc.interaction();
        if (im.blockBreakingCooldown() > delay_.i) im.setBlockBreakingCooldown(delay_.i);
    }

private:
    Setting& delay_ = addInt("Delay (ticks)", 0, 0, 4);
};
}  // namespace

std::unique_ptr<Module> makeFastBreak() { return std::make_unique<FastBreak>(); }
