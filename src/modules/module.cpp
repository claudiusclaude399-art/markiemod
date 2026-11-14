#include "module.hpp"

const char* categoryName(Category c) {
    switch (c) {
        case Category::Visual:   return "Visual";
        case Category::Movement: return "Movement";
        case Category::World:    return "World";
    }
    return "?";
}

Module::Module(std::string name, std::string description, Category category, bool needsWorld)
    : name_(std::move(name)), description_(std::move(description)), category_(category), needsWorld_(needsWorld) {}

Setting& Module::addBool(const std::string& name, bool def) {
    auto s = std::make_unique<Setting>();
    s->name = name;
    s->type = Setting::Type::Bool;
    s->b = def;
    settings_.push_back(std::move(s));
    return *settings_.back();
}

Setting& Module::addFloat(const std::string& name, float def, float min, float max, const char* fmt) {
    auto s = std::make_unique<Setting>();
    s->name = name;
    s->type = Setting::Type::Float;
    s->f = def;
    s->fMin = min;
    s->fMax = max;
    s->format = fmt;
    settings_.push_back(std::move(s));
    return *settings_.back();
}

Setting& Module::addInt(const std::string& name, int def, int min, int max) {
    auto s = std::make_unique<Setting>();
    s->name = name;
    s->type = Setting::Type::Int;
    s->i = def;
    s->iMin = min;
    s->iMax = max;
    s->format = "%d";
    settings_.push_back(std::move(s));
    return *settings_.back();
}
