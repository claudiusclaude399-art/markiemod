#include "sdk.hpp"

#include "../core/log.hpp"
#include "../jni/jvm.hpp"
#include "mappings.hpp"

namespace sdk {

namespace {
using jni::ClassRef;
using jni::FieldRef;
using jni::MethodRef;
using jni::jv;

// Small helpers so each declaration below is one line.
#define FIELD(var, cls, member)   FieldRef  var(cls, member.name, member.sig)
#define SFIELD(var, cls, member)  FieldRef  var(cls, member.name, member.sig, true)
#define METHOD(var, cls, member)  MethodRef var(cls, member.name, member.sig)
#define SMETHOD(var, cls, member) MethodRef var(cls, member.name, member.sig, true)

// ----- classes (resolved in this order) ---------------------------------------
ClassRef cMinecraft(map::MinecraftClient);
ClassRef cMouse(map::Mouse);
ClassRef cKeyBinding(map::KeyBinding);
ClassRef cGameOptions(map::GameOptions);
ClassRef cSimpleOption(map::SimpleOption);
ClassRef cEntity(map::Entity);
ClassRef cLiving(map::LivingEntity);
ClassRef cPlayer(map::PlayerEntity);
ClassRef cClientPlayer(map::ClientPlayerEntity);
ClassRef cAbilities(map::PlayerAbilities);
ClassRef cVec3d(map::Vec3d);
ClassRef cAttributes(map::EntityAttributes);
ClassRef cAttrInstance(map::EntityAttributeInstance);
ClassRef cNetHandler(map::ClientCommonNetworkHandler);
ClassRef cOnGroundOnly(map::OnGroundOnlyPacket);
ClassRef cInteraction(map::InteractionManager);
ClassRef cGameMode(map::GameMode);
ClassRef cWorld(map::World);
ClassRef cClientWorld(map::ClientWorld);
ClassRef cWorldProps(map::ClientWorldProperties);

// ----- members ---------------------------------------------------------------
SMETHOD(mcGetInstance, cMinecraft, map::mc::getInstance);
FIELD(mcPlayer, cMinecraft, map::mc::player);
FIELD(mcWorld, cMinecraft, map::mc::world);
FIELD(mcOptions, cMinecraft, map::mc::options);
FIELD(mcMouse, cMinecraft, map::mc::mouse);
FIELD(mcInteraction, cMinecraft, map::mc::interactionManager);
FIELD(mcScreen, cMinecraft, map::mc::currentScreen);
FIELD(mcItemUseCooldown, cMinecraft, map::mc::itemUseCooldown);

METHOD(mouseLock, cMouse, map::mouse::lockCursor);
METHOD(mouseUnlock, cMouse, map::mouse::unlockCursor);
METHOD(mouseIsLocked, cMouse, map::mouse::isCursorLocked);

FIELD(keyPressed, cKeyBinding, map::key::pressed);
SMETHOD(keyUnpressAll, cKeyBinding, map::key::unpressAll);

FIELD(optGamma, cGameOptions, map::opt::gamma);
FIELD(optFov, cGameOptions, map::opt::fov);
FIELD(optSprintKey, cGameOptions, map::opt::sprintKey);
FIELD(optHudHidden, cGameOptions, map::opt::hudHidden);
FIELD(optSmoothCamera, cGameOptions, map::opt::smoothCamera);

FIELD(soValue, cSimpleOption, map::simpleopt::value);

FIELD(entAge, cEntity, map::entity::age);
FIELD(entOnGround, cEntity, map::entity::onGround);
FIELD(entHorizontalCollision, cEntity, map::entity::horizontalCollision);
FIELD(entFallDistance, cEntity, map::entity::fallDistance);
METHOD(entGetVelocity, cEntity, map::entity::getVelocity);
METHOD(entSetVelocity, cEntity, map::entity::setVelocity);
METHOD(entIsSneaking, cEntity, map::entity::isSneaking);

FIELD(vecX, cVec3d, map::vec3d::x);
FIELD(vecY, cVec3d, map::vec3d::y);
FIELD(vecZ, cVec3d, map::vec3d::z);

METHOD(livGetAttribute, cLiving, map::living::getAttributeInstance);
METHOD(livIsGliding, cLiving, map::living::isGliding);

METHOD(plGetAbilities, cPlayer, map::playerent::getAbilities);
FIELD(cpNetworkHandler, cClientPlayer, map::clientplayer::networkHandler);

FIELD(abFlying, cAbilities, map::abilities::flying);
FIELD(abAllowFlying, cAbilities, map::abilities::allowFlying);
FIELD(abFlySpeed, cAbilities, map::abilities::flySpeed);

SFIELD(attrMovementSpeed, cAttributes, map::attrs::MOVEMENT_SPEED);
SFIELD(attrStepHeight, cAttributes, map::attrs::STEP_HEIGHT);
SFIELD(attrJumpStrength, cAttributes, map::attrs::JUMP_STRENGTH);
METHOD(aiGetBase, cAttrInstance, map::attrinst::getBaseValue);
METHOD(aiSetBase, cAttrInstance, map::attrinst::setBaseValue);

METHOD(netSendPacket, cNetHandler, map::net::sendPacket);
METHOD(onGroundOnlyCtor, cOnGroundOnly, map::net::onGroundOnlyCtor);

FIELD(imBreakCooldown, cInteraction, map::interact::blockBreakingCooldown);
METHOD(imGetGameMode, cInteraction, map::interact::getCurrentGameMode);
METHOD(gmIsSurvivalLike, cGameMode, map::gamemode::isSurvivalLike);

METHOD(worldSetRain, cWorld, map::world::setRainGradient);
METHOD(worldSetThunder, cWorld, map::world::setThunderGradient);
METHOD(cwGetProperties, cClientWorld, map::world::getLevelProperties);
METHOD(propsSetTimeOfDay, cWorldProps, map::world::setTimeOfDay);

jobject g_minecraft = nullptr;  // global ref to MinecraftClient.instance (never changes)

const FieldRef& attributeField(Attribute a) {
    switch (a) {
        case Attribute::MovementSpeed: return attrMovementSpeed;
        case Attribute::StepHeight:    return attrStepHeight;
        case Attribute::JumpStrength:  break;
    }
    return attrJumpStrength;
}
}  // namespace

// ----- lifecycle ---------------------------------------------------------------

bool init() {
    jobject local = jni::callStaticObject(mcGetInstance);
    if (!local) { logf("[sdk] MinecraftClient.getInstance() returned null"); return false; }
    g_minecraft = jni::env()->NewGlobalRef(local);
    jni::env()->DeleteLocalRef(local);
    return g_minecraft != nullptr;
}

void shutdown() {
    if (g_minecraft && jni::env()) jni::env()->DeleteGlobalRef(g_minecraft);
    g_minecraft = nullptr;
}

double defaultBase(Attribute a) {
    switch (a) {
        case Attribute::MovementSpeed: return map::attrs::DEFAULT_MOVEMENT_SPEED;
        case Attribute::StepHeight:    return map::attrs::DEFAULT_STEP_HEIGHT;
        case Attribute::JumpStrength:  break;
    }
    return map::attrs::DEFAULT_JUMP_STRENGTH;
}

// ----- KeyBinding ------------------------------------------------------------
bool KeyBinding::pressed() const { return jni::getBool(o, keyPressed); }
void KeyBinding::setPressedRaw(bool v) const { jni::setBool(o, keyPressed, v); }
void KeyBinding::unpressAll() { jni::callStaticVoid(keyUnpressAll); }

// ----- SimpleOption ----------------------------------------------------------
double SimpleOption::getDouble(double fallback) const { return jni::unboxDouble(jni::getObject(o, soValue), fallback); }
void SimpleOption::setDouble(double v) const {
    if (jobject boxed = jni::boxDouble(v)) jni::setObject(o, soValue, boxed);
}
int SimpleOption::getInt(int fallback) const { return static_cast<int>(jni::unboxInt(jni::getObject(o, soValue), fallback)); }
void SimpleOption::setInt(int v) const {
    if (jobject boxed = jni::boxInt(static_cast<jint>(v))) jni::setObject(o, soValue, boxed);
}

// ----- GameOptions -----------------------------------------------------------
SimpleOption GameOptions::gamma() const { return SimpleOption(jni::getObject(o, optGamma)); }
SimpleOption GameOptions::fov() const { return SimpleOption(jni::getObject(o, optFov)); }
KeyBinding GameOptions::sprintKey() const { return KeyBinding(jni::getObject(o, optSprintKey)); }
bool GameOptions::hudHidden() const { return jni::getBool(o, optHudHidden); }
bool GameOptions::smoothCamera() const { return jni::getBool(o, optSmoothCamera); }
void GameOptions::setSmoothCamera(bool v) const { jni::setBool(o, optSmoothCamera, v); }

// ----- Mouse -------------------------------------------------------------------
void Mouse::lockCursor() const { jni::callVoid(o, mouseLock); }
void Mouse::unlockCursor() const { jni::callVoid(o, mouseUnlock); }
bool Mouse::isCursorLocked() const { return jni::callBool(o, mouseIsLocked); }

// ----- Abilities ---------------------------------------------------------------
bool Abilities::flying() const { return jni::getBool(o, abFlying); }
void Abilities::setFlying(bool v) const { jni::setBool(o, abFlying, v); }
bool Abilities::allowFlying() const { return jni::getBool(o, abAllowFlying); }
void Abilities::setAllowFlying(bool v) const { jni::setBool(o, abAllowFlying, v); }
float Abilities::flySpeed() const { return jni::getFloat(o, abFlySpeed); }
void Abilities::setFlySpeed(float v) const { jni::setFloat(o, abFlySpeed, v); }

// ----- AttributeInstance -------------------------------------------------------
double AttributeInstance::base() const { return jni::callDouble(o, aiGetBase); }
void AttributeInstance::setBase(double v) const {
    jvalue a[1] = { jv(static_cast<jdouble>(v)) };
    jni::callVoid(o, aiSetBase, a);
}

// ----- Entity ------------------------------------------------------------------
int Entity::age() const { return static_cast<int>(jni::getInt(o, entAge)); }
bool Entity::onGround() const { return jni::getBool(o, entOnGround); }
bool Entity::horizontalCollision() const { return jni::getBool(o, entHorizontalCollision); }
double Entity::fallDistance() const { return jni::getDouble(o, entFallDistance); }
Vec3 Entity::velocity() const {
    jobject v = jni::callObject(o, entGetVelocity);
    if (!v) return {};
    return { jni::getDouble(v, vecX), jni::getDouble(v, vecY), jni::getDouble(v, vecZ) };
}
void Entity::setVelocity(const Vec3& v) const {
    jvalue a[3] = { jv(static_cast<jdouble>(v.x)), jv(static_cast<jdouble>(v.y)), jv(static_cast<jdouble>(v.z)) };
    jni::callVoid(o, entSetVelocity, a);
}
bool Entity::sneaking() const { return jni::callBool(o, entIsSneaking); }

AttributeInstance LivingEntity::attribute(Attribute a) const {
    if (!o) return AttributeInstance();
    jobject entry = jni::getStaticObject(attributeField(a));
    if (!entry) return AttributeInstance();
    jvalue args[1] = { jv(entry) };
    return AttributeInstance(jni::callObject(o, livGetAttribute, args));
}
bool LivingEntity::gliding() const { return jni::callBool(o, livIsGliding); }

Abilities PlayerEntity::abilities() const { return Abilities(jni::callObject(o, plGetAbilities)); }

// ----- network -----------------------------------------------------------------
void NetworkHandler::sendOnGround(bool onGround, bool horizontalCollision) const {
    if (!o) return;
    jvalue a[2] = { jv(onGround), jv(horizontalCollision) };
    jobject packet = jni::newObject(onGroundOnlyCtor, a);
    if (!packet) return;
    jvalue p[1] = { jv(packet) };
    jni::callVoid(o, netSendPacket, p);
}
NetworkHandler ClientPlayer::network() const { return NetworkHandler(jni::getObject(o, cpNetworkHandler)); }

// ----- InteractionManager ------------------------------------------------------
int InteractionManager::blockBreakingCooldown() const { return static_cast<int>(jni::getInt(o, imBreakCooldown)); }
void InteractionManager::setBlockBreakingCooldown(int v) const { jni::setInt(o, imBreakCooldown, static_cast<jint>(v)); }
bool InteractionManager::survivalLike() const {
    jobject mode = jni::callObject(o, imGetGameMode);
    return mode && jni::callBool(mode, gmIsSurvivalLike);
}

// ----- ClientWorld -------------------------------------------------------------
void ClientWorld::setRainGradient(float v) const {
    jvalue a[1] = { jv(static_cast<jfloat>(v)) };
    jni::callVoid(o, worldSetRain, a);
}
void ClientWorld::setThunderGradient(float v) const {
    jvalue a[1] = { jv(static_cast<jfloat>(v)) };
    jni::callVoid(o, worldSetThunder, a);
}
void ClientWorld::setTimeOfDay(long long ticks) const {
    jobject props = jni::callObject(o, cwGetProperties);
    if (!props) return;
    jvalue a[1] = { jv(static_cast<jlong>(ticks)) };
    jni::callVoid(props, propsSetTimeOfDay, a);
}

// ----- Minecraft ---------------------------------------------------------------
Minecraft Minecraft::get() { return Minecraft(g_minecraft); }
ClientPlayer Minecraft::player() const { return ClientPlayer(jni::getObject(o, mcPlayer)); }
ClientWorld Minecraft::world() const { return ClientWorld(jni::getObject(o, mcWorld)); }
GameOptions Minecraft::options() const { return GameOptions(jni::getObject(o, mcOptions)); }
Mouse Minecraft::mouse() const { return Mouse(jni::getObject(o, mcMouse)); }
InteractionManager Minecraft::interaction() const { return InteractionManager(jni::getObject(o, mcInteraction)); }
bool Minecraft::screenOpen() const { return jni::getObject(o, mcScreen) != nullptr; }
int Minecraft::itemUseCooldown() const { return static_cast<int>(jni::getInt(o, mcItemUseCooldown)); }
void Minecraft::setItemUseCooldown(int v) const { jni::setInt(o, mcItemUseCooldown, static_cast<jint>(v)); }

}  // namespace sdk
