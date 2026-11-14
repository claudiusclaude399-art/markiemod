#pragma once
#include <memory>

#include "module.hpp"

// One factory per module. Add a module: write the .cpp, declare it here,
// add it to registerAll() in manager.cpp and to CMakeLists.txt.
std::unique_ptr<Module> makeFullbright();
std::unique_ptr<Module> makeZoom();
std::unique_ptr<Module> makeTimeChanger();
std::unique_ptr<Module> makeClearWeather();

std::unique_ptr<Module> makeAutoSprint();
std::unique_ptr<Module> makeFlight();
std::unique_ptr<Module> makeSpeed();
std::unique_ptr<Module> makeStep();
std::unique_ptr<Module> makeHighJump();

std::unique_ptr<Module> makeNoFall();
std::unique_ptr<Module> makeFastPlace();
std::unique_ptr<Module> makeFastBreak();
