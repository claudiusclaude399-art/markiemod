#include "config.hpp"

#include <cstdlib>
#include <fstream>
#include <string>

#include "../modules/manager.hpp"
#include "log.hpp"

namespace config {

namespace {
constexpr const char* kFile = "markiemod.cfg";
ClientSettings g_client;

std::string trim(const std::string& s) {
    size_t a = s.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) return {};
    size_t b = s.find_last_not_of(" \t\r\n");
    return s.substr(a, b - a + 1);
}

void applyModuleValue(Module& m, const std::string& k, const std::string& v) {
    if (k == "enabled") { m.enabled = v == "1"; return; }
    if (k == "key") { m.key = std::atoi(v.c_str()) & 0xFF; return; }
    if (k == "mode") { m.bindMode = v == "hold" ? BindMode::Hold : BindMode::Toggle; return; }
    for (auto& s : m.settings()) {
        if (s->name != k) continue;
        switch (s->type) {
            case Setting::Type::Bool: s->b = v == "1"; break;
            case Setting::Type::Float: {
                float f = static_cast<float>(std::atof(v.c_str()));
                s->f = f < s->fMin ? s->fMin : (f > s->fMax ? s->fMax : f);
                break;
            }
            case Setting::Type::Int: {
                int i = std::atoi(v.c_str());
                s->i = i < s->iMin ? s->iMin : (i > s->iMax ? s->iMax : i);
                break;
            }
        }
    }
}
}  // namespace

ClientSettings& client() { return g_client; }

void load() {
    std::ifstream in(kFile);
    if (!in) { logf("[config] no %s yet, using defaults", kFile); return; }
    std::string line, section;
    while (std::getline(in, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        if (line.front() == '[' && line.back() == ']') { section = line.substr(1, line.size() - 2); continue; }
        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string k = trim(line.substr(0, eq)), v = trim(line.substr(eq + 1));
        if (section == "Client") {
            if (k == "hud") g_client.hud = v == "1";
        } else if (Module* m = modules::find(section)) {
            applyModuleValue(*m, k, v);
        }
    }
    logf("[config] loaded %s", kFile);
}

void save() {
    std::ofstream out(kFile, std::ios::trunc);
    if (!out) { logf("[config] ERROR: cannot write %s", kFile); return; }
    out << "# markiemod config (Minecraft 1.21.11, Fabric)\n";
    out << "[Client]\nhud=" << (g_client.hud ? 1 : 0) << "\n";
    for (auto& m : modules::all()) {
        out << "\n[" << m->name() << "]\n";
        out << "enabled=" << (m->enabled && m->bindMode == BindMode::Toggle ? 1 : 0) << "\n";
        out << "key=" << m->key << "\n";
        out << "mode=" << (m->bindMode == BindMode::Hold ? "hold" : "toggle") << "\n";
        for (auto& s : m->settings()) {
            out << s->name << "=";
            switch (s->type) {
                case Setting::Type::Bool: out << (s->b ? 1 : 0); break;
                case Setting::Type::Float: out << s->f; break;
                case Setting::Type::Int: out << s->i; break;
            }
            out << "\n";
        }
    }
}

}  // namespace config
