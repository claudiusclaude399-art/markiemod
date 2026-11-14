#include "../all.hpp"

// The integrated server takes `onGround` from the client's movement packets.
// While falling, an extra OnGroundOnly(true) packet each tick resets the server's
// fall distance before it reaches the 3-block damage threshold.
//
// One tick of terminal-velocity falling moves ~3.9 blocks, which alone would be
// enough for damage, so "Limit fall speed" caps downward speed just below that.
namespace {
class NoFall : public Module {
public:
    NoFall() : Module("No Fall", "No fall damage", Category::World) {}

    void onTick(Ctx& ctx) override {
        auto& p = ctx.player;
        if (p.gliding() || p.abilities().flying()) return;  // elytra / creative flight handle themselves
        if (p.fallDistance() <= 2.0) return;

        if (limitSpeed_.b) {
            sdk::Vec3 v = p.velocity();
            if (v.y < -kMaxFallPerTick) {
                v.y = -kMaxFallPerTick;
                p.setVelocity(v);
            }
        }
        p.network().sendOnGround(true, p.horizontalCollision());
    }

private:
    static constexpr double kMaxFallPerTick = 2.5;
    Setting& limitSpeed_ = addBool("Limit fall speed", true);
};
}  // namespace

std::unique_ptr<Module> makeNoFall() { return std::make_unique<NoFall>(); }
