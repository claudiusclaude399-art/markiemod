#pragma once
// ---------------------------------------------------------------------------
// Every Minecraft name the client touches, in one place.
//
// Target: Fabric, Minecraft 1.21.11 (intermediary namespace, yarn 1.21.11+build.4
// used only to look the names up). These are hardcoded on purpose: this client
// targets exactly this version and is never updated.
//
// Comment on each line = yarn name, so the code stays readable.
// Class names use '/' separators (JNI style); the loader converts as needed.
// ---------------------------------------------------------------------------

namespace map {

// ----- classes -------------------------------------------------------------
inline constexpr const char* MinecraftClient            = "net/minecraft/class_310";            // MinecraftClient
inline constexpr const char* Mouse                      = "net/minecraft/class_312";            // Mouse
inline constexpr const char* KeyBinding                 = "net/minecraft/class_304";            // KeyBinding
inline constexpr const char* GameOptions                = "net/minecraft/class_315";            // GameOptions
inline constexpr const char* SimpleOption               = "net/minecraft/class_7172";           // SimpleOption
inline constexpr const char* Entity                     = "net/minecraft/class_1297";           // Entity
inline constexpr const char* LivingEntity               = "net/minecraft/class_1309";           // LivingEntity
inline constexpr const char* PlayerEntity               = "net/minecraft/class_1657";           // PlayerEntity
inline constexpr const char* ClientPlayerEntity         = "net/minecraft/class_746";            // ClientPlayerEntity
inline constexpr const char* PlayerAbilities            = "net/minecraft/class_1656";           // PlayerAbilities
inline constexpr const char* Vec3d                      = "net/minecraft/class_243";            // Vec3d
inline constexpr const char* EntityAttributes           = "net/minecraft/class_5134";           // EntityAttributes
inline constexpr const char* EntityAttributeInstance    = "net/minecraft/class_1324";           // EntityAttributeInstance
inline constexpr const char* ClientCommonNetworkHandler = "net/minecraft/class_8673";           // ClientCommonNetworkHandler
inline constexpr const char* OnGroundOnlyPacket         = "net/minecraft/class_2828$class_5911"; // PlayerMoveC2SPacket$OnGroundOnly
inline constexpr const char* InteractionManager         = "net/minecraft/class_636";            // ClientPlayerInteractionManager
inline constexpr const char* GameMode                   = "net/minecraft/class_1934";           // GameMode
inline constexpr const char* World                      = "net/minecraft/class_1937";           // World
inline constexpr const char* ClientWorld                = "net/minecraft/class_638";            // ClientWorld
inline constexpr const char* ClientWorldProperties      = "net/minecraft/class_638$class_5271"; // ClientWorld$Properties

// ----- descriptors used more than once ---------------------------------------
#define MM_L(cls) "L" cls ";"
#define MM_MinecraftClient    "net/minecraft/class_310"
#define MM_Mouse              "net/minecraft/class_312"
#define MM_KeyBinding         "net/minecraft/class_304"
#define MM_GameOptions        "net/minecraft/class_315"
#define MM_SimpleOption       "net/minecraft/class_7172"
#define MM_ClientPlayerEntity "net/minecraft/class_746"
#define MM_PlayerAbilities    "net/minecraft/class_1656"
#define MM_Vec3d              "net/minecraft/class_243"
#define MM_RegistryEntry      "net/minecraft/class_6880"
#define MM_AttributeInstance  "net/minecraft/class_1324"
#define MM_PlayNetHandler     "net/minecraft/class_634"
#define MM_Packet             "net/minecraft/class_2596"
#define MM_InteractionManager "net/minecraft/class_636"
#define MM_GameMode           "net/minecraft/class_1934"
#define MM_ClientWorld        "net/minecraft/class_638"
#define MM_Screen             "net/minecraft/class_437"
#define MM_ClientWorldProps   "net/minecraft/class_638$class_5271"

// ----- members ---------------------------------------------------------------
// Each entry: { name, descriptor }

struct Member { const char* name; const char* sig; };

namespace mc {  // MinecraftClient
inline constexpr Member getInstance        { "method_1551", "()" MM_L(MM_MinecraftClient) };      // static getInstance()
inline constexpr Member player             { "field_1724",  MM_L(MM_ClientPlayerEntity) };        // player
inline constexpr Member world              { "field_1687",  MM_L(MM_ClientWorld) };               // world
inline constexpr Member options            { "field_1690",  MM_L(MM_GameOptions) };               // options
inline constexpr Member mouse              { "field_1729",  MM_L(MM_Mouse) };                     // mouse
inline constexpr Member interactionManager { "field_1761",  MM_L(MM_InteractionManager) };        // interactionManager
inline constexpr Member currentScreen      { "field_1755",  MM_L(MM_Screen) };                    // currentScreen
inline constexpr Member itemUseCooldown    { "field_1752",  "I" };                                // itemUseCooldown (private)
}

namespace mouse {  // Mouse
inline constexpr Member lockCursor     { "method_1612", "()V" };  // lockCursor()
inline constexpr Member unlockCursor   { "method_1610", "()V" };  // unlockCursor()
inline constexpr Member isCursorLocked { "method_1613", "()Z" };  // isCursorLocked()
}

namespace key {  // KeyBinding
inline constexpr Member pressed    { "field_1653",  "Z" };    // pressed (written directly: StickyKeyBinding.setPressed toggles)
inline constexpr Member unpressAll { "method_1437", "()V" };  // static unpressAll()
}

namespace opt {  // GameOptions
inline constexpr Member gamma          { "field_1840", MM_L(MM_SimpleOption) };  // gamma   : SimpleOption<Double>
inline constexpr Member fov            { "field_1826", MM_L(MM_SimpleOption) };  // fov     : SimpleOption<Integer>
inline constexpr Member sprintKey      { "field_1867", MM_L(MM_KeyBinding) };    // sprintKey
inline constexpr Member hudHidden      { "field_1842", "Z" };                    // hudHidden (F1)
inline constexpr Member smoothCamera   { "field_1914", "Z" };                    // smoothCameraEnabled
}

namespace simpleopt {  // SimpleOption
inline constexpr Member value { "field_37868", "Ljava/lang/Object;" };  // value (written directly: setValue() clamps)
}

namespace entity {  // Entity
inline constexpr Member age                 { "field_6012",  "I" };       // age (incremented once per client tick)
inline constexpr Member onGround            { "field_5952",  "Z" };       // onGround
inline constexpr Member horizontalCollision { "field_5976",  "Z" };       // horizontalCollision
inline constexpr Member fallDistance        { "field_6017",  "D" };       // fallDistance (double since 1.21.5)
inline constexpr Member getVelocity         { "method_18798", "()" MM_L(MM_Vec3d) };  // getVelocity()
inline constexpr Member setVelocity         { "method_18800", "(DDD)V" };             // setVelocity(double,double,double)
inline constexpr Member isSneaking          { "method_5715", "()Z" };     // isSneaking()
}

namespace vec3d {  // Vec3d
inline constexpr Member x { "field_1352", "D" };
inline constexpr Member y { "field_1351", "D" };
inline constexpr Member z { "field_1350", "D" };
}

namespace living {  // LivingEntity
inline constexpr Member getAttributeInstance { "method_5996", "(" MM_L(MM_RegistryEntry) ")" MM_L(MM_AttributeInstance) };
inline constexpr Member isGliding            { "method_6128", "()Z" };
}

namespace playerent {  // PlayerEntity
inline constexpr Member getAbilities { "method_31549", "()" MM_L(MM_PlayerAbilities) };
}

namespace clientplayer {  // ClientPlayerEntity
inline constexpr Member networkHandler { "field_3944", MM_L(MM_PlayNetHandler) };
}

namespace abilities {  // PlayerAbilities
inline constexpr Member flying      { "field_7479", "Z" };
inline constexpr Member allowFlying { "field_7478", "Z" };
inline constexpr Member flySpeed    { "field_7481", "F" };
}

namespace attrs {  // EntityAttributes (static RegistryEntry<EntityAttribute>)
inline constexpr Member MOVEMENT_SPEED { "field_23719", MM_L(MM_RegistryEntry) };
inline constexpr Member STEP_HEIGHT    { "field_47761", MM_L(MM_RegistryEntry) };
inline constexpr Member JUMP_STRENGTH  { "field_23728", MM_L(MM_RegistryEntry) };

// Vanilla player base values in 1.21.11 (PlayerEntity.createPlayerAttributes / attribute defaults)
inline constexpr double DEFAULT_MOVEMENT_SPEED = 0.10000000149011612;
inline constexpr double DEFAULT_STEP_HEIGHT    = 0.6;
inline constexpr double DEFAULT_JUMP_STRENGTH  = 0.41999998688697815;
}

namespace attrinst {  // EntityAttributeInstance
inline constexpr Member getBaseValue { "method_6201", "()D" };
inline constexpr Member setBaseValue { "method_6192", "(D)V" };
}

namespace net {  // ClientCommonNetworkHandler / packets
inline constexpr Member sendPacket       { "method_52787", "(" MM_L(MM_Packet) ")V" };
inline constexpr Member onGroundOnlyCtor { "<init>", "(ZZ)V" };  // OnGroundOnly(boolean onGround, boolean horizontalCollision)
}

namespace interact {  // ClientPlayerInteractionManager
inline constexpr Member blockBreakingCooldown { "field_3716",  "I" };
inline constexpr Member getCurrentGameMode    { "method_2920", "()" MM_L(MM_GameMode) };
}

namespace gamemode {  // GameMode
inline constexpr Member isSurvivalLike { "method_8388", "()Z" };
}

namespace world {  // World / ClientWorld
inline constexpr Member setRainGradient    { "method_8519",  "(F)V" };
inline constexpr Member setThunderGradient { "method_8496",  "(F)V" };
inline constexpr Member getLevelProperties { "method_28104", "()" MM_L(MM_ClientWorldProps) };  // ClientWorld.getLevelProperties()
inline constexpr Member setTimeOfDay       { "method_165",   "(J)V" };                          // ClientWorld$Properties.setTimeOfDay(long)
}

}  // namespace map
