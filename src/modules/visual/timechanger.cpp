#include "../all.hpp"

// Client-side time of day. The server keeps its own time; after disabling,
// the next server time sync (within a second) puts the sky back.
namespace {
class TimeChanger : public Module {
public:
    TimeChanger() : Module("Time Changer", "Client-side time of day", Category::Visual) {}
    void onFrame(Ctx& ctx) override { ctx.world.setTimeOfDay(time_.i); }

private:
    Setting& time_ = addInt("Time (ticks)", 6000, 0, 23999);  // 0 sunrise, 6000 noon, 18000 midnight
};
}  // namespace

std::unique_ptr<Module> makeTimeChanger() { return std::make_unique<TimeChanger>(); }
