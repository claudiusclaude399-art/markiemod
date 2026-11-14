#include "../all.hpp"

// Hides rain and thunder client-side. Vanilla fades the gradients back after disabling.
namespace {
class ClearWeather : public Module {
public:
    ClearWeather() : Module("Clear Weather", "Hide rain and thunder (client-side)", Category::Visual) {}
    void onFrame(Ctx& ctx) override {
        ctx.world.setRainGradient(0.f);
        ctx.world.setThunderGradient(0.f);
    }
};
}  // namespace

std::unique_ptr<Module> makeClearWeather() { return std::make_unique<ClearWeather>(); }
