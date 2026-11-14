#include "../all.hpp"

// Hold-to-zoom by lowering the FOV option (written directly, bypassing the 30..110 clamp).
// Default bind: C, hold mode.
namespace {
class Zoom : public Module {
public:
    Zoom() : Module("Zoom", "Hold to zoom in", Category::Visual, /*needsWorld*/ false) {
        key = 'C';
        bindMode = BindMode::Hold;
    }

    void onEnable(Ctx& ctx) override {
        int f = ctx.options.fov().getInt(70);
        originalFov_ = (f >= 30 && f <= 110) ? f : 70;
        originalSmooth_ = ctx.options.smoothCamera();
    }
    void onFrame(Ctx& ctx) override {
        ctx.options.fov().setInt(fov_.i);
        if (smooth_.b) ctx.options.setSmoothCamera(true);
    }
    void onDisable(Ctx& ctx) override {
        ctx.options.fov().setInt(originalFov_);
        ctx.options.setSmoothCamera(originalSmooth_);
    }

private:
    Setting& fov_ = addInt("Zoom FOV", 20, 5, 60);
    Setting& smooth_ = addBool("Smooth camera", true);
    int originalFov_ = 70;
    bool originalSmooth_ = false;
};
}  // namespace

std::unique_ptr<Module> makeZoom() { return std::make_unique<Zoom>(); }
