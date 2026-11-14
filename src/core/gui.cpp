#include "gui.hpp"

#include <algorithm>
#include <cstdio>

#include "../modules/manager.hpp"
#include "config.hpp"
#include "imgui.h"
#include "imgui_impl_opengl3.h"
#include "imgui_impl_win32.h"
#include "input.hpp"
#include "log.hpp"

namespace gui {

namespace {
bool g_ready = false;
Module* g_bindTarget = nullptr;

// GL entry points not exported by opengl32.dll (GL 1.1 only).
using PFNBindFramebuffer = void(APIENTRY*)(unsigned int target, unsigned int fbo);
using PFNBindBuffer = void(APIENTRY*)(unsigned int target, unsigned int buffer);
using PFNGetIntegerv = void(APIENTRY*)(unsigned int pname, int* data);
PFNBindFramebuffer pglBindFramebuffer = nullptr;
PFNBindBuffer pglBindBuffer = nullptr;
PFNGetIntegerv pglGetIntegerv = nullptr;
constexpr unsigned int GL_DRAW_FRAMEBUFFER_ = 0x8CA9;
constexpr unsigned int GL_DRAW_FRAMEBUFFER_BINDING_ = 0x8CA6;
constexpr unsigned int GL_PIXEL_UNPACK_BUFFER_ = 0x88EC;
constexpr unsigned int GL_PIXEL_UNPACK_BUFFER_BINDING_ = 0x88EF;

void applyStyle() {
    ImGui::StyleColorsDark();
    ImGuiStyle& s = ImGui::GetStyle();
    s.WindowRounding = 6.f;
    s.FrameRounding = 4.f;
    s.GrabRounding = 4.f;
    s.TabRounding = 4.f;
    s.WindowPadding = ImVec2(10, 10);
    s.ItemSpacing = ImVec2(8, 6);
    ImVec4* c = s.Colors;
    const ImVec4 accent(0.36f, 0.55f, 0.95f, 1.f);
    c[ImGuiCol_WindowBg] = ImVec4(0.07f, 0.07f, 0.09f, 0.96f);
    c[ImGuiCol_TitleBgActive] = ImVec4(0.12f, 0.14f, 0.20f, 1.f);
    c[ImGuiCol_CheckMark] = accent;
    c[ImGuiCol_SliderGrab] = accent;
    c[ImGuiCol_SliderGrabActive] = ImVec4(0.50f, 0.68f, 1.f, 1.f);
    c[ImGuiCol_Tab] = ImVec4(0.13f, 0.14f, 0.18f, 1.f);
    c[ImGuiCol_TabHovered] = ImVec4(0.30f, 0.45f, 0.80f, 1.f);
    c[ImGuiCol_TabSelected] = ImVec4(0.24f, 0.36f, 0.66f, 1.f);
    c[ImGuiCol_Header] = ImVec4(0.18f, 0.22f, 0.32f, 1.f);
    c[ImGuiCol_HeaderHovered] = ImVec4(0.24f, 0.32f, 0.50f, 1.f);
}

void drawSetting(Setting& s) {
    switch (s.type) {
        case Setting::Type::Bool:
            ImGui::Checkbox(s.name.c_str(), &s.b);
            break;
        case Setting::Type::Float:
            ImGui::SliderFloat(s.name.c_str(), &s.f, s.fMin, s.fMax, s.format.c_str(), ImGuiSliderFlags_AlwaysClamp);
            break;
        case Setting::Type::Int:
            ImGui::SliderInt(s.name.c_str(), &s.i, s.iMin, s.iMax, s.format.c_str(), ImGuiSliderFlags_AlwaysClamp);
            break;
    }
}

void drawModule(Module& m) {
    ImGui::PushID(m.name().c_str());

    const bool holdBound = m.bindMode == BindMode::Hold && m.key != 0;
    ImGui::BeginDisabled(holdBound);  // hold-mode modules follow their key
    ImGui::Checkbox("##on", &m.enabled);
    ImGui::EndDisabled();
    ImGui::SameLine();

    bool open = ImGui::TreeNodeEx(m.name().c_str(), ImGuiTreeNodeFlags_SpanAvailWidth);
    if (ImGui::IsItemHovered()) ImGui::SetTooltip("%s", m.description().c_str());
    if (m.active) {
        ImGui::SameLine();
        ImGui::TextColored(ImVec4(0.45f, 0.85f, 0.45f, 1.f), "active");
    }

    if (open) {
        ImGui::PushItemWidth(200.f);
        for (auto& s : m.settings()) drawSetting(*s);
        ImGui::PopItemWidth();

        const bool waiting = g_bindTarget == &m && input::capturing();
        char label[96];
        snprintf(label, sizeof(label), "Key: %s###bind", waiting ? "press a key (Esc = none)" : input::keyName(m.key));
        if (ImGui::Button(label)) {
            g_bindTarget = &m;
            input::beginCapture();
        }
        ImGui::SameLine();
        int mode = m.bindMode == BindMode::Hold ? 1 : 0;
        ImGui::SetNextItemWidth(90.f);
        if (ImGui::Combo("##mode", &mode, "Toggle\0Hold\0")) {
            m.bindMode = mode == 1 ? BindMode::Hold : BindMode::Toggle;
            if (m.bindMode == BindMode::Hold) m.enabled = false;
        }
        ImGui::TreePop();
    }
    ImGui::PopID();
}

void drawClientTab(const std::vector<std::string>& errors) {
    auto& cs = config::client();
    ImGui::Checkbox("Show module list (HUD)", &cs.hud);
    ImGui::Separator();
    ImGui::Text("Menu: %s    Unload: %s", input::keyName(input::kMenuKey), input::keyName(input::kUnloadKey));
    ImGui::TextDisabled("Target: Minecraft 1.21.11 (Fabric), single player");
    if (ImGui::Button("Save config")) config::save();
    ImGui::SameLine();
    if (ImGui::Button("Unload")) input::requestUnload();

    if (!errors.empty()) {
        ImGui::Separator();
        ImGui::TextColored(ImVec4(1.f, 0.4f, 0.4f, 1.f), "Unresolved mappings (%zu):", errors.size());
        for (const auto& e : errors) ImGui::BulletText("%s", e.c_str());
    }

    ImGui::Separator();
    ImGui::TextDisabled("Log (also written to markiemod.log)");
    ImGui::BeginChild("log", ImVec2(0, 0), ImGuiChildFlags_Borders);
    for (const auto& l : logLines()) ImGui::TextUnformatted(l.c_str());
    if (ImGui::GetScrollY() >= ImGui::GetScrollMaxY()) ImGui::SetScrollHereY(1.f);
    ImGui::EndChild();
}

void drawMenu(Ctx* ctx, const std::vector<std::string>& errors) {
    ImGui::SetNextWindowSize(ImVec2(470, 440), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowPos(ImVec2(60, 60), ImGuiCond_FirstUseEver);
    bool open = true;
    if (ImGui::Begin("markiemod", &open, ImGuiWindowFlags_NoCollapse)) {
        if (!ctx) {
            ImGui::TextColored(ImVec4(1.f, 0.4f, 0.4f, 1.f), "JNI layer failed to start - modules are disabled.");
            ImGui::TextWrapped("See the Client tab / markiemod.log. This client only supports Fabric 1.21.11.");
        } else if (!errors.empty()) {
            ImGui::TextColored(ImVec4(1.f, 0.6f, 0.3f, 1.f), "Some mappings are missing - see the Client tab.");
        }

        if (ImGui::BeginTabBar("tabs")) {
            if (ctx) {
                for (Category cat : {Category::Visual, Category::Movement, Category::World}) {
                    if (!ImGui::BeginTabItem(categoryName(cat))) continue;
                    for (auto& m : modules::all())
                        if (m->category() == cat) drawModule(*m);
                    ImGui::EndTabItem();
                }
            }
            if (ImGui::BeginTabItem("Client")) {
                drawClientTab(errors);
                ImGui::EndTabItem();
            }
            ImGui::EndTabBar();
        }
    }
    ImGui::End();
    if (!open) input::setMenuOpen(false);

    int vk;
    if (g_bindTarget && input::takeCaptured(vk)) {
        g_bindTarget->key = vk;
        g_bindTarget = nullptr;
    }
}

void drawHud(Ctx& ctx) {
    if (!config::client().hud || !ctx.inWorld || ctx.options.hudHidden()) return;

    std::vector<const Module*> active;
    for (auto& m : modules::all())
        if (m->active) active.push_back(m.get());
    if (active.empty()) return;
    std::sort(active.begin(), active.end(), [](const Module* a, const Module* b) {
        return ImGui::CalcTextSize(a->name().c_str()).x > ImGui::CalcTextSize(b->name().c_str()).x;
    });

    ImDrawList* dl = ImGui::GetForegroundDrawList();
    const float right = ImGui::GetIO().DisplaySize.x - 4.f;
    const float lineH = ImGui::GetTextLineHeight() + 2.f;
    float y = 4.f;
    for (const Module* m : active) {
        ImVec2 sz = ImGui::CalcTextSize(m->name().c_str());
        ImVec2 a(right - sz.x - 8.f, y), b(right, y + lineH);
        dl->AddRectFilled(a, b, IM_COL32(10, 10, 14, 150));
        dl->AddRectFilled(ImVec2(b.x - 2.f, a.y), b, IM_COL32(92, 140, 242, 255));
        dl->AddText(ImVec2(a.x + 4.f, y + 1.f), IM_COL32(235, 235, 240, 255), m->name().c_str());
        y += lineH;
    }
}
}  // namespace

bool init(HWND window) {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = "markiemod-imgui.ini";  // window position/size, in the game directory
    io.LogFilename = nullptr;
    applyStyle();

    if (!ImGui_ImplWin32_Init(window)) { logf("[gui] ERROR: ImGui Win32 init failed"); return false; }
    if (!ImGui_ImplOpenGL3_Init("#version 150")) { logf("[gui] ERROR: ImGui OpenGL3 init failed"); return false; }

    pglBindFramebuffer = reinterpret_cast<PFNBindFramebuffer>(wglGetProcAddress("glBindFramebuffer"));
    pglBindBuffer = reinterpret_cast<PFNBindBuffer>(wglGetProcAddress("glBindBuffer"));
    pglGetIntegerv = reinterpret_cast<PFNGetIntegerv>(GetProcAddress(GetModuleHandleW(L"opengl32.dll"), "glGetIntegerv"));
    g_ready = true;
    logf("[gui] ImGui %s ready", IMGUI_VERSION);
    return true;
}

void shutdown() {
    if (!g_ready) return;
    ImGui_ImplOpenGL3_Shutdown();
    ImGui_ImplWin32_Shutdown();
    ImGui::DestroyContext();
    g_ready = false;
}

void render(Ctx* ctx, const std::vector<std::string>& errors) {
    if (!g_ready) return;
    ImGuiIO& io = ImGui::GetIO();
    // Only touch the OS cursor while the menu is open; otherwise the game owns it.
    if (input::menuOpen()) io.ConfigFlags &= ~ImGuiConfigFlags_NoMouseCursorChange;
    else io.ConfigFlags |= ImGuiConfigFlags_NoMouseCursorChange;

    // ImGui uploads its font texture from client memory: no unpack PBO may be bound.
    int prevUnpack = 0;
    if (pglGetIntegerv) pglGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING_, &prevUnpack);
    if (pglBindBuffer && prevUnpack != 0) pglBindBuffer(GL_PIXEL_UNPACK_BUFFER_, 0);

    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplWin32_NewFrame();
    ImGui::NewFrame();

    if (input::menuOpen()) drawMenu(ctx, errors);
    if (ctx) drawHud(*ctx);

    ImGui::Render();

    // Draw into the window's default framebuffer, then put back whatever was bound.
    int prevDraw = 0;
    if (pglGetIntegerv) pglGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING_, &prevDraw);
    if (pglBindFramebuffer && prevDraw != 0) pglBindFramebuffer(GL_DRAW_FRAMEBUFFER_, 0);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    if (pglBindFramebuffer && prevDraw != 0) pglBindFramebuffer(GL_DRAW_FRAMEBUFFER_, static_cast<unsigned int>(prevDraw));
    if (pglBindBuffer && prevUnpack != 0) pglBindBuffer(GL_PIXEL_UNPACK_BUFFER_, static_cast<unsigned int>(prevUnpack));
}

}  // namespace gui
