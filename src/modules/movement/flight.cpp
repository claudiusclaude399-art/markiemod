#include "../all.hpp"

// Creative-style flight via the player's abilities, so vanilla handles the
// controls (jump = up, sneak = down) and the camera.
//
// Single player: the integrated server allows flight (isFlightEnabled() is true),
// but it does not know we are flying, so it would count descending as falling.
// "No fall damage" resets that with an on-ground packet while descending.
namespace {
class Flight : public Module {
public:
    Flight() : Module("Flight", "Fly like in creative", Category::Movement) {}

    void onEnable(Ctx& ctx) override {
        auto ab = ctx.player.abilities();
        ab.setAllowFlying(true);
        ab.setFlying(true);
    }
    void onTick(Ctx& ctx) override {
        auto ab = ctx.player.abilities();
        ab.setAllowFlying(true);
        if (keepFlying_.b && !ctx.player.onGround()) ab.setFlying(true);
        ab.setFlySpeed(speed_.f);

        if (antiFall_.b && ab.flying() && ctx.player.velocity().y < -0.05)
            ctx.player.network().sendOnGround(true, ctx.player.horizontalCollision());
    }
    void onDisable(Ctx& ctx) override {
        auto ab = ctx.player.abilities();
        ab.setFlySpeed(kVanillaFlySpeed);
        if (ctx.mc.interaction().survivalLike()) {  // keep creative/spectator flight intact
            ab.setFlying(false);
            ab.setAllowFlying(false);
        }
    }

private:
    static constexpr float kVanillaFlySpeed = 0.05f;
    Setting& speed_ = addFloat("Speed", 0.10f, 0.01f, 0.50f, "%.2f");
    Setting& keepFlying_ = addBool("Keep flying", true);  // re-enable flight after touching the ground
    Setting& antiFall_ = addBool("No fall damage", true);
};
}  // namespace

std::unique_ptr<Module> makeFlight() { return std::make_unique<Flight>(); }
