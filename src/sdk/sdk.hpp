#pragma once
// Typed wrappers over Minecraft objects. Modules only ever use these, never raw
// JNI, so all name/descriptor knowledge stays in mappings.hpp + sdk.cpp.
//
// Every wrapper holds a LOCAL ref that lives until the end of the current frame
// (see jni::LocalFrame). Never store a wrapper across frames.
// Every call is null-safe: on a null object it does nothing / returns a default.

#include <jni.h>

namespace sdk {

struct Vec3 { double x = 0, y = 0, z = 0; };

struct Obj {
    jobject o = nullptr;
    Obj() = default;
    explicit Obj(jobject obj) : o(obj) {}
    explicit operator bool() const { return o != nullptr; }
};

struct KeyBinding : Obj {
    using Obj::Obj;
    bool pressed() const;
    void setPressedRaw(bool v) const;  // writes the field; safe for toggle-mode (sticky) keys
    static void unpressAll();
};

// SimpleOption<T>. value is written straight to the field: setValue() would clamp it.
struct SimpleOption : Obj {
    using Obj::Obj;
    double getDouble(double fallback) const;
    void setDouble(double v) const;
    int getInt(int fallback) const;
    void setInt(int v) const;
};

struct GameOptions : Obj {
    using Obj::Obj;
    SimpleOption gamma() const;
    SimpleOption fov() const;
    KeyBinding sprintKey() const;
    bool hudHidden() const;
    bool smoothCamera() const;
    void setSmoothCamera(bool v) const;
};

struct Mouse : Obj {
    using Obj::Obj;
    void lockCursor() const;
    void unlockCursor() const;
    bool isCursorLocked() const;
};

struct Abilities : Obj {
    using Obj::Obj;
    bool flying() const;
    void setFlying(bool v) const;
    bool allowFlying() const;
    void setAllowFlying(bool v) const;
    float flySpeed() const;
    void setFlySpeed(float v) const;
};

struct AttributeInstance : Obj {
    using Obj::Obj;
    double base() const;
    void setBase(double v) const;
};

enum class Attribute { MovementSpeed, StepHeight, JumpStrength };
double defaultBase(Attribute a);  // vanilla player base value

struct Entity : Obj {
    using Obj::Obj;
    int age() const;
    bool onGround() const;
    bool horizontalCollision() const;
    double fallDistance() const;
    Vec3 velocity() const;
    void setVelocity(const Vec3& v) const;
    bool sneaking() const;
};

struct LivingEntity : Entity {
    using Entity::Entity;
    AttributeInstance attribute(Attribute a) const;
    bool gliding() const;
};

struct PlayerEntity : LivingEntity {
    using LivingEntity::LivingEntity;
    Abilities abilities() const;
};

struct NetworkHandler : Obj {
    using Obj::Obj;
    // Sends PlayerMoveC2SPacket.OnGroundOnly(onGround, horizontalCollision).
    void sendOnGround(bool onGround, bool horizontalCollision) const;
};

struct ClientPlayer : PlayerEntity {
    using PlayerEntity::PlayerEntity;
    NetworkHandler network() const;
};

struct InteractionManager : Obj {
    using Obj::Obj;
    int blockBreakingCooldown() const;
    void setBlockBreakingCooldown(int v) const;
    bool survivalLike() const;  // survival or adventure
};

struct ClientWorld : Obj {
    using Obj::Obj;
    void setRainGradient(float v) const;
    void setThunderGradient(float v) const;
    void setTimeOfDay(long long ticks) const;  // client-side only
};

struct Minecraft : Obj {
    using Obj::Obj;
    static Minecraft get();  // the singleton (cached global ref)
    ClientPlayer player() const;
    ClientWorld world() const;
    GameOptions options() const;
    Mouse mouse() const;
    InteractionManager interaction() const;
    bool screenOpen() const;
    int itemUseCooldown() const;
    void setItemUseCooldown(int v) const;
};

// Call after jni::resolveAll() succeeded / before jni::shutdown().
bool init();
void shutdown();

}  // namespace sdk
