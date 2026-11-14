#include "attribute_module.hpp"

// 0.42 = vanilla, ~0.6 = 2 blocks, ~0.75 = 3 blocks. Pair with No Fall for big values.
std::unique_ptr<Module> makeHighJump() {
    return std::make_unique<AttributeModule>("High Jump", "Jump higher (jump strength attribute)",
                                             sdk::Attribute::JumpStrength, "Strength", 0.60f, 0.42f, 2.0f, false);
}
