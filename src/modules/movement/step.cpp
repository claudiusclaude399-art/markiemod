#include "attribute_module.hpp"

std::unique_ptr<Module> makeStep() {
    return std::make_unique<AttributeModule>("Step", "Walk up full blocks (step height attribute)",
                                             sdk::Attribute::StepHeight, "Height", 1.0f, 0.6f, 3.0f, false);
}
