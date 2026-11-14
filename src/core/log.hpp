#pragma once
#include <string>
#include <vector>

// Log to markiemod.log (in the game directory) and keep the last lines for the menu.
void logf(const char* fmt, ...);
std::vector<std::string> logLines();
void logClose();
