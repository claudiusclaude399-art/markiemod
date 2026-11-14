#pragma once

// Plain-text config in the game directory (markiemod.cfg):
//   [Client]          hud=1
//   [Module Name]     enabled=0  key=67  mode=hold  <Setting Name>=<value>
namespace config {

struct ClientSettings {
    bool hud = true;  // list of active modules in the top-right corner
};
ClientSettings& client();

void load();
void save();

}  // namespace config
