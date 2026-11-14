#include "../all.hpp"

// Caps MinecraftClient.itemUseCooldown (vanilla: 4 ticks between right-click uses).
namespace {
class FastPlace : public Module {
public:
    FastPlace() : Module("Fast Place", "Lower the right-click delay", Category::World) {}
    void onFrame(Ctx& ctx) override {
        if (ctx.mc.itemUseCooldown() > delay_.i) ctx.mc.setItemUseCooldown(delay_.i);
    }

private:
    Setting& delay_ = addInt("Delay (ticks)", 0, 0, 3);
};
}  // namespace

std::unique_ptr<Module> makeFastPlace() { return std::make_unique<FastPlace>(); }
