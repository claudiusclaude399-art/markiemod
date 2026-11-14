#include "../all.hpp"

// Raises the gamma option past the slider's 0..1 range (the field is written
// directly; SimpleOption.setValue would clamp). The lightmap reads it every
// update, so this works everywhere, including the nether and caves.
namespace {
class Fullbright : public Module {
public:
    Fullbright() : Module("Fullbright", "See in the dark (gamma boost)", Category::Visual, /*needsWorld*/ false) {}

    void onEnable(Ctx& ctx) override {
        double g = ctx.options.gamma().getDouble(0.5);
        original_ = (g >= 0.0 && g <= 1.0) ? g : 0.5;  // never "restore" a boosted value
    }
    void onFrame(Ctx& ctx) override { ctx.options.gamma().setDouble(brightness_.f); }
    void onDisable(Ctx& ctx) override { ctx.options.gamma().setDouble(original_); }

private:
    Setting& brightness_ = addFloat("Brightness", 16.f, 1.f, 16.f, "%.1f");
    double original_ = 0.5;
};
}  // namespace

std::unique_ptr<Module> makeFullbright() { return std::make_unique<Fullbright>(); }
