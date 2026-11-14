#include "attribute_module.hpp"

std::unique_ptr<Module> makeSpeed() {
    return std::make_unique<AttributeModule>("Speed", "Walk faster (movement speed attribute)",
                                             sdk::Attribute::MovementSpeed, "Multiplier", 1.8f, 1.0f, 5.0f, true);
}
