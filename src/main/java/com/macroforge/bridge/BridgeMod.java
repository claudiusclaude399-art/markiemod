package com.macroforge.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.macroforge.bridge.mixin.KeyMappingAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.InteractionResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MacroForge Bridge – client-side Fabric mod.
 * Streams game state + events to the MacroForge app and executes its commands on the client thread.
 */
public final class BridgeMod implements ClientModInitializer {
    public static final String VERSION = "1.0.0";
    public static final Logger LOG = LoggerFactory.getLogger("macroforge_bridge");
    static BridgeServer server;

    // state tracking for events
    private static int tick;
    private static float lastHealth = -1;
    private static boolean wasDead;
    private static int lastHandTotems = -1;
    private static int lastSlot = -1;
    private static String lastHeld = null;
    private static String lastScreen = "";
    private static int lastLevel = -1;
    private static int lastFood = -1;
    private static String lastStateJson = "";
    private static String lastCrosshair = "";
    private static String lastTargetFlags = "";
    private static boolean lastTargetBlocking;
    private static int hits, combo, previousSlot = -1;
    private static boolean lastFalling, lastOnGround = true, lastGliding, lastInWater, lastRising;
    private static double peakFall;
    private static int airTicks;
    private static final Map<KeyMapping, Integer> releaseAt = new HashMap<>();
    private static final Set<KeyMapping> held = new HashSet<>();
    private static LookAnim look;

    private static final class LookAnim { float fromYaw, fromPitch, toYaw, toPitch; int total, done; }

    @Override
    public void onInitializeClient() {
        server = new BridgeServer(BridgeMod::onCommand);
        server.start();

        ClientTickEvents.END_CLIENT_TICK.register(BridgeMod::onTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> event(overlay ? "actionbar" : "chat", message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> event("chat", message.getString()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> event("join", serverName(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { releaseAll(); event("leave", ""); });
        // fires on the client when *you* hit an entity
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() && player == Minecraft.getInstance().player) {
                hits++; combo++;
                JsonObject e = eventObj("attack", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())) + " " + entity.getName().getString());
                e.addProperty("id", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
                e.addProperty("name", entity.getName().getString());
                e.addProperty("is_player", entity instanceof Player);
                e.addProperty("combo", combo);
                send(e);
            }
            return InteractionResult.PASS;
        });
    }

    static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    static void onClientConnected() { lastStateJson = ""; }

    // ------------------------------------------------------------------ events

    private static JsonObject eventObj(String name, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "event");
        o.addProperty("event", name);
        o.addProperty("text", text == null ? "" : text);
        return o;
    }

    private static void event(String name, String text) { send(eventObj(name, text)); }

    private static void send(JsonObject o) { if (server != null && server.isConnected()) server.send(o); }

    private static void onTick(Minecraft mc) {
        tick++;
        if (server != null) server.drain();

        // keep "held" keys pressed even if Minecraft re-polls the keyboard
        for (KeyMapping k : held) k.setDown(true);
        if (!releaseAt.isEmpty()) {
            Iterator<Map.Entry<KeyMapping, Integer>> it = releaseAt.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<KeyMapping, Integer> e = it.next();
                if (tick >= e.getValue()) { if (!held.contains(e.getKey())) e.getKey().setDown(false); it.remove(); }
            }
        }

        LocalPlayer p = mc.player;
        if (look != null && p != null) {
            look.done++;
            float t = Math.min(1f, look.done / (float) look.total);
            float s = t * t * (3 - 2 * t); // smoothstep
            p.setYRot(look.fromYaw + (look.toYaw - look.fromYaw) * s);
            p.setXRot(look.fromPitch + (look.toPitch - look.fromPitch) * s);
            if (t >= 1f) look = null;
        }

        if (server == null || !server.isConnected()) return;
        if (p == null) { if (tick % 20 == 0) sendStateIfChanged(mc); return; }

        // ---- derived events
        float hp = p.getHealth();
        boolean dead = p.isDeadOrDying() || hp <= 0;
        if (lastHealth >= 0 && hp < lastHealth) combo = 0; // got hit: combo broken
        if (lastHealth >= 0 && hp < lastHealth && !dead) {
            JsonObject e = eventObj("damage", fmt(lastHealth - hp));
            e.addProperty("amount", lastHealth - hp);
            e.addProperty("health", hp);
            send(e);
        }
        if (dead && !wasDead) event("death", "");
        if (!dead && wasDead) event("respawn", "");
        wasDead = dead;
        lastHealth = hp;

        int handTotems = countTotem(p.getMainHandItem()) + countTotem(p.getOffhandItem());
        if (lastHandTotems > handTotems && !dead && mc.screen == null) event("totem_pop", "");
        lastHandTotems = handTotems;

        int slot = selectedSlot(p);
        if (slot != lastSlot && lastSlot >= 0) event("slot", String.valueOf(slot + 1));
        lastSlot = slot;

        String heldName = p.getMainHandItem().isEmpty() ? "" : p.getMainHandItem().getHoverName().getString();
        if (lastHeld != null && !heldName.equals(lastHeld)) event("held_item", heldName);
        lastHeld = heldName;

        String scr = mc.screen == null ? "" : screenName(mc);
        if (!scr.equals(lastScreen)) event(scr.isEmpty() ? "screen_close" : "screen_open", scr.isEmpty() ? lastScreen : scr);
        lastScreen = scr;

        if (lastLevel >= 0 && p.experienceLevel > lastLevel) event("level_up", String.valueOf(p.experienceLevel));
        lastLevel = p.experienceLevel;

        int food = p.getFoodData().getFoodLevel();
        if (lastFood >= 0 && food < lastFood) event("hunger", String.valueOf(food));
        lastFood = food;

        // ---- movement: falling / landing / jumping / gliding / water
        Move mv = movement(mc, p);
        if (mv.onGround || mv.inWater || mv.climbing) airTicks = 0; else airTicks++;
        if (mv.falling) peakFall = Math.max(peakFall, mv.fallDistance);
        boolean moveChanged = false;
        if (mv.falling && !lastFalling) { event("start_falling", fmt((float) mv.heightAboveGround)); moveChanged = true; }
        if (mv.onGround && !lastOnGround) {
            JsonObject e = eventObj("landed", fmt((float) peakFall));
            e.addProperty("fall_distance", round(peakFall));
            send(e);
            peakFall = 0; moveChanged = true;
        }
        if (mv.rising && !lastRising && lastOnGround) { event("jump", ""); moveChanged = true; }
        if (mv.gliding != lastGliding) { event(mv.gliding ? "start_gliding" : "stop_gliding", ""); moveChanged = true; }
        if (mv.inWater != lastInWater) { event(mv.inWater ? "enter_water" : "leave_water", ""); moveChanged = true; }
        if (!mv.falling && !mv.gliding) { if (mv.onGround || mv.inWater) peakFall = 0; }
        lastFalling = mv.falling; lastOnGround = mv.onGround; lastRising = mv.rising; lastGliding = mv.gliding; lastInWater = mv.inWater;
        if (moveChanged) sendStateIfChanged(mc);

        // crosshair target changes → crosshair_entity / crosshair_block / crosshair_none
        Crosshair ch = crosshair(mc, p);
        if (!ch.key.equals(lastCrosshair))
        {
            lastCrosshair = ch.key;
            JsonObject e = eventObj(ch.type.equals("entity") ? "crosshair_entity" : ch.type.equals("block") ? "crosshair_block" : "crosshair_none", ch.id + (ch.name.isEmpty() ? "" : " " + ch.name));
            e.addProperty("target_type", ch.type);
            e.addProperty("id", ch.id);
            e.addProperty("name", ch.name);
            e.addProperty("distance", round(ch.distance));
            e.addProperty("is_player", ch.isPlayer);
            send(e);
            if (tick % 4 != 0) sendStateIfChanged(mc); // state follows immediately so conditions see it
        }
        // target raises / lowers a shield, starts eating, gets hit… → push state right away
        String flags = ch.key + "|" + ch.blocking + "|" + ch.usingItem + "|" + ch.hurt;
        if (!flags.equals(lastTargetFlags)) {
            lastTargetFlags = flags;
            if (ch.type.equals("entity") && ch.blocking != lastTargetBlocking) event(ch.blocking ? "target_shield_up" : "target_shield_down", ch.id + " " + ch.name);
            lastTargetBlocking = ch.type.equals("entity") && ch.blocking;
            sendStateIfChanged(mc);
        }

        if (tick % 4 == 0) sendStateIfChanged(mc);
    }

    static final class Move {
        boolean onGround, falling, rising, gliding, flying, swimming, inWater, inLava, climbing, riding, sleeping, moving;
        double fallDistance, heightAboveGround, speed, vx, vy, vz;
        String standingOn = "", chestItem = "";
    }

    static Move movement(Minecraft mc, LocalPlayer p) {
        Move m = new Move();
        Vec3 v = p.getDeltaMovement();
        m.vx = v.x; m.vy = v.y; m.vz = v.z;
        m.onGround = p.onGround();
        m.inWater = p.isInWater();
        m.inLava = p.isInLava();
        m.climbing = p.onClimbable();
        m.swimming = p.isSwimming();
        m.riding = p.isPassenger();
        m.sleeping = p.isSleeping();
        m.gliding = p.isFallFlying();
        m.flying = p.getAbilities().flying;
        m.fallDistance = (double) p.fallDistance;
        m.speed = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0; // blocks per second
        m.moving = m.speed > 0.2 || Math.abs(v.y) > 0.1 && !m.onGround;
        boolean airborne = !m.onGround && !m.inWater && !m.inLava && !m.climbing && !m.flying && !m.riding && !m.gliding;
        m.falling = airborne && v.y < -0.1;
        m.rising = airborne && v.y > 0.05;
        // distance to the first non-air block below
        BlockPos feet = BlockPos.containing(p.getX(), p.getY(), p.getZ());
        m.heightAboveGround = 0;
        for (int i = 0; i <= 384; i++) {
            BlockPos b = feet.below(i);
            if (b.getY() < mc.level.getMinY()) { m.heightAboveGround = 999; break; }
            if (!mc.level.getBlockState(b).isAir()) {
                m.heightAboveGround = Math.max(0, p.getY() - (b.getY() + 1));
                if (i <= 1) m.standingOn = String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b).getBlock()));
                break;
            }
        }
        if (m.standingOn.isEmpty() && m.onGround) {
            BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
            m.standingOn = String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(below).getBlock()));
        }
        m.chestItem = itemId(p.getItemBySlot(EquipmentSlot.CHEST));
        return m;
    }

    static final class Crosshair {
        String key = "none", type = "none", id = "", name = "", held = "", offhand = "", useItem = "";
        double distance; boolean isPlayer, hostile, blocking, usingItem, sneaking, hurt; float health = -1, maxHealth; int bx, by, bz, entityId = -1, armor;
    }

    static Crosshair crosshair(Minecraft mc, LocalPlayer p) {
        Crosshair c = new Crosshair();
        HitResult hit = mc.hitResult;
        if (hit == null || mc.level == null) return c;
        if (hit instanceof EntityHitResult eh) {
            Entity en = eh.getEntity();
            c.type = "entity";
            c.id = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()));
            c.name = en.getName().getString();
            c.distance = p.distanceTo(en);
            c.isPlayer = en instanceof Player;
            c.hostile = en instanceof Enemy;
            c.entityId = en.getId();
            c.sneaking = en.isShiftKeyDown();
            if (en instanceof LivingEntity le) {
                c.health = le.getHealth();
                c.maxHealth = le.getMaxHealth();
                c.armor = le.getArmorValue();
                c.held = itemId(le.getMainHandItem());
                c.offhand = itemId(le.getOffhandItem());
                c.usingItem = le.isUsingItem();
                c.useItem = c.usingItem ? itemId(le.getUseItem()) : "";
                // shields only block after being raised for a moment (5 ticks)
                c.blocking = c.usingItem && c.useItem.endsWith("shield") && le.getTicksUsingItem() >= 5;
                c.hurt = le.hurtTime > 0;
            }
            c.key = "e:" + en.getId();
        } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            c.type = "block";
            c.id = String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bh.getBlockPos()).getBlock()));
            c.bx = bh.getBlockPos().getX(); c.by = bh.getBlockPos().getY(); c.bz = bh.getBlockPos().getZ();
            c.distance = p.getEyePosition().distanceTo(hit.getLocation());
            c.key = "b:" + c.id + "@" + c.bx + "," + c.by + "," + c.bz;
        }
        return c;
    }

    private static String fmt(float f) { return String.format(Locale.ROOT, "%.1f", f); }

    private static int countTotem(ItemStack s) { return !s.isEmpty() && itemId(s).endsWith("totem_of_undying") ? s.getCount() : 0; }

    static String itemId(ItemStack s) {
        if (s == null || s.isEmpty()) return "";
        return String.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem()));
    }

    /** Selected hotbar slot 0-8, found without relying on version-specific inventory fields. */
    private static int selectedSlot(LocalPlayer p) {
        Inventory inv = p.getInventory();
        ItemStack main = p.getMainHandItem();
        for (int i = 0; i < 9; i++) if (inv.getItem(i) == main) return i;
        return lastSlot < 0 ? 0 : lastSlot; // empty hand: keep the last known slot
    }

    private static String screenName(Minecraft mc) {
        try { String t = mc.screen.getTitle().getString(); return t.isEmpty() ? "screen" : t; } catch (Exception e) { return "screen"; }
    }

    private static String serverName(Minecraft mc) {
        try { return mc.getCurrentServer() != null ? mc.getCurrentServer().ip : (mc.hasSingleplayerServer() ? "singleplayer" : ""); } catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------ state snapshot

    private static void sendStateIfChanged(Minecraft mc) {
        JsonObject s = new JsonObject();
        s.addProperty("type", "state");
        LocalPlayer p = mc.player;
        s.addProperty("in_world", p != null && mc.level != null);
        s.addProperty("fps", mc.getFps());
        s.addProperty("server", serverName(mc));
        if (p != null && mc.level != null) {
            s.addProperty("name", p.getName().getString());
            s.addProperty("health", round(p.getHealth()));
            s.addProperty("max_health", round(p.getMaxHealth()));
            s.addProperty("absorption", round(p.getAbsorptionAmount()));
            s.addProperty("food", p.getFoodData().getFoodLevel());
            s.addProperty("saturation", round(p.getFoodData().getSaturationLevel()));
            s.addProperty("armor", p.getArmorValue());
            s.addProperty("xp_level", p.experienceLevel);
            s.addProperty("x", round(p.getX()));
            s.addProperty("y", round(p.getY()));
            s.addProperty("z", round(p.getZ()));
            s.addProperty("block_x", (int) Math.floor(p.getX()));
            s.addProperty("block_y", (int) Math.floor(p.getY()));
            s.addProperty("block_z", (int) Math.floor(p.getZ()));
            s.addProperty("yaw", round(wrap(p.getYRot())));
            s.addProperty("pitch", round(p.getXRot()));
            s.addProperty("on_ground", p.onGround());
            s.addProperty("sprinting", p.isSprinting());
            s.addProperty("sneaking", p.isShiftKeyDown());
            s.addProperty("in_water", p.isInWater());
            s.addProperty("on_fire", p.isOnFire());
            s.addProperty("dead", p.isDeadOrDying());
            String dim = mc.level.dimension().toString(); // ResourceKey[minecraft:dimension / minecraft:overworld]
            int slash = dim.lastIndexOf('/');
            s.addProperty("dimension", (slash >= 0 ? dim.substring(slash + 1) : dim).replace("]", "").trim());
            s.addProperty("time", mc.level.getDayTime() % 24000L);
            s.addProperty("raining", mc.level.isRaining());
            int slot = selectedSlot(p);
            s.addProperty("slot", slot + 1);
            ItemStack main = p.getMainHandItem(), off = p.getOffhandItem();
            s.addProperty("held_item", main.isEmpty() ? "" : main.getHoverName().getString());
            s.addProperty("held_id", itemId(main));
            s.addProperty("held_count", main.isEmpty() ? 0 : main.getCount());
            s.addProperty("held_damage", main.isEmpty() ? 0 : main.getDamageValue());
            s.addProperty("held_max_damage", main.isEmpty() ? 0 : main.getMaxDamage());
            s.addProperty("offhand_item", off.isEmpty() ? "" : off.getHoverName().getString());
            s.addProperty("offhand_id", itemId(off));
            Inventory inv = p.getInventory();
            JsonArray hotbar = new JsonArray();
            int totems = 0, pearls = 0, gapples = 0, crystals = 0, emptySlots = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (i < 9) hotbar.add(st.isEmpty() ? "" : itemId(st));
                if (i < 36 && st.isEmpty()) emptySlots++;
                if (st.isEmpty()) continue;
                String id = itemId(st);
                if (id.endsWith("totem_of_undying")) totems += st.getCount();
                else if (id.endsWith("ender_pearl")) pearls += st.getCount();
                else if (id.endsWith("golden_apple")) gapples += st.getCount();
                else if (id.endsWith("end_crystal")) crystals += st.getCount();
            }
            s.add("hotbar", hotbar);
            s.addProperty("totems", totems);
            s.addProperty("pearls", pearls);
            s.addProperty("gapples", gapples);
            s.addProperty("crystals", crystals);
            s.addProperty("empty_slots", emptySlots);
            s.addProperty("screen", mc.screen == null ? "" : screenName(mc));
            Move mv = movement(mc, p);
            s.addProperty("falling", mv.falling);
            s.addProperty("rising", mv.rising);
            s.addProperty("in_air", !mv.onGround && !mv.inWater && !mv.climbing);
            s.addProperty("gliding", mv.gliding);
            s.addProperty("flying", mv.flying);
            s.addProperty("swimming", mv.swimming);
            s.addProperty("in_lava", mv.inLava);
            s.addProperty("climbing", mv.climbing);
            s.addProperty("riding", mv.riding);
            s.addProperty("sleeping", mv.sleeping);
            s.addProperty("moving", mv.moving);
            s.addProperty("fall_distance", round(mv.fallDistance));
            s.addProperty("height_above_ground", round(mv.heightAboveGround));
            s.addProperty("speed", round(mv.speed));
            s.addProperty("velocity_x", round(mv.vx * 20));
            s.addProperty("velocity_y", round(mv.vy * 20));
            s.addProperty("velocity_z", round(mv.vz * 20));
            s.addProperty("air_ticks", airTicks);
            s.addProperty("standing_on", mv.standingOn);
            s.addProperty("chest_item", mv.chestItem);
            s.addProperty("elytra_equipped", mv.chestItem.endsWith("elytra"));

            Crosshair ch = crosshair(mc, p);
            s.addProperty("looking_at", ch.id);
            s.addProperty("looking_type", ch.type);
            s.addProperty("looking_name", ch.name);
            s.addProperty("looking_distance", round(ch.distance));
            s.addProperty("looking_is_player", ch.isPlayer);
            s.addProperty("looking_hostile", ch.hostile);
            s.addProperty("looking_health", ch.health < 0 ? 0 : round(ch.health));
            s.addProperty("looking_entity_id", ch.entityId);
            s.addProperty("looking_blocking", ch.blocking);
            s.addProperty("looking_using_item", ch.usingItem);
            s.addProperty("looking_use_item", ch.useItem);
            s.addProperty("looking_held", ch.held);
            s.addProperty("looking_offhand", ch.offhand);
            s.addProperty("looking_armor", ch.armor);
            s.addProperty("looking_max_health", round(ch.maxHealth));
            s.addProperty("looking_sneaking", ch.sneaking);
            s.addProperty("looking_hurt", ch.hurt);
            s.addProperty("hits", hits);
            s.addProperty("combo", combo);
            s.addProperty("looking_block_x", ch.bx);
            s.addProperty("looking_block_y", ch.by);
            s.addProperty("looking_block_z", ch.bz);
            s.addProperty("attack_cooldown", round(p.getAttackStrengthScale(0f)));
            s.addProperty("attack_ready", p.getAttackStrengthScale(0f) >= 1f);

            // nearby players / hostile mobs
            int players = 0, hostiles = 0; String nearest = ""; double nearestDist = 0;
            for (Player other : mc.level.players()) {
                if (other == p) continue;
                double d = p.distanceTo(other);
                if (d > 64) continue;
                players++;
                if (nearest.isEmpty() || d < nearestDist) { nearest = other.getName().getString(); nearestDist = d; }
            }
            for (Entity en : mc.level.entitiesForRendering()) {
                if (en instanceof Enemy && en.isAlive() && p.distanceTo(en) <= 16) hostiles++;
            }
            s.addProperty("nearby_players", players);
            s.addProperty("nearest_player", nearest);
            s.addProperty("nearest_player_distance", round(nearestDist));
            s.addProperty("nearby_hostiles", hostiles);

            StringBuilder fx = new StringBuilder();
            for (MobEffectInstance ef : p.getActiveEffects()) {
                String id = ef.getDescriptionId();
                int dot = id.lastIndexOf('.');
                if (fx.length() > 0) fx.append(',');
                fx.append(dot >= 0 ? id.substring(dot + 1) : id);
            }
            s.addProperty("effects", fx.toString());
        }
        String json = s.toString();
        if (json.equals(lastStateJson)) return;
        lastStateJson = json;
        server.send(s);
    }

    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }
    private static float wrap(float yaw) { yaw %= 360f; if (yaw >= 180f) yaw -= 360f; if (yaw < -180f) yaw += 360f; return yaw; }

    // ------------------------------------------------------------------ commands (run on the client thread)

    private static String str(JsonObject o, String k, String def) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def; }
    private static double num(JsonObject o, String k, double def) { try { return o.has(k) ? o.get(k).getAsDouble() : def; } catch (Exception e) { return def; } }
    private static boolean bool(JsonObject o, String k) { try { return o.has(k) && o.get(k).getAsBoolean(); } catch (Exception e) { return false; } }

    static KeyMapping findKey(Minecraft mc, String name) {
        if (name == null || name.isEmpty()) return null;
        String n = name.startsWith("key.") ? name : "key." + name;
        for (KeyMapping k : mc.options.keyMappings) if (k.getName().equalsIgnoreCase(n) || k.getName().equalsIgnoreCase(name)) return k;
        return null;
    }

    private static void click(KeyMapping k, int ticks) {
        KeyMappingAccessor a = (KeyMappingAccessor) k;
        a.macroforge$setClickCount(a.macroforge$getClickCount() + 1);
        k.setDown(true);
        releaseAt.put(k, tick + Math.max(1, ticks));
    }

    private static void releaseAll() {
        for (KeyMapping k : held) k.setDown(false);
        held.clear();
    }

    private static void onCommand(JsonObject m) {
        Minecraft mc = Minecraft.getInstance();
        String op = str(m, "op", "");
        LocalPlayer p = mc.player;
        JsonObject r = new JsonObject();
        r.addProperty("type", "result");
        if (m.has("id")) r.add("id", m.get("id"));
        boolean ok = true;
        String err = null;

        switch (op) {
            case "chat": {
                if (p == null) { ok = false; err = "not in a world"; break; }
                String text = str(m, "text", "");
                if (text.startsWith("/")) p.connection.sendCommand(text.substring(1));
                else p.connection.sendChat(text);
                break;
            }
            case "key": {
                KeyMapping k = findKey(mc, str(m, "name", ""));
                if (k == null) { ok = false; err = "unknown key binding: " + str(m, "name", ""); break; }
                String mode = str(m, "mode", "press");
                if (mode.equals("down")) { held.add(k); k.setDown(true); KeyMappingAccessor a = (KeyMappingAccessor) k; a.macroforge$setClickCount(a.macroforge$getClickCount() + 1); }
                else if (mode.equals("up")) { held.remove(k); k.setDown(false); }
                else click(k, (int) num(m, "ticks", 1));
                break;
            }
            case "slot": {
                int s = (int) num(m, "slot", 1);
                if (s == 0) { // 0 = go back to the slot before the last switch
                    if (previousSlot < 0) { ok = false; err = "no previous slot yet"; break; }
                    s = previousSlot + 1;
                }
                if (s < 1 || s > 9) { ok = false; err = "slot must be 1-9 (or 0 = previous)"; break; }
                if (p != null) previousSlot = selectedSlot(p);
                click(mc.options.keyHotbarSlots[s - 1], 1);
                break;
            }
            case "select_item": {
                if (p == null) { ok = false; err = "not in a world"; break; }
                String q = str(m, "name", "").toLowerCase(Locale.ROOT);
                int found = -1;
                for (int i = 0; i < 9 && found < 0; i++) {
                    ItemStack st = p.getInventory().getItem(i);
                    if (st.isEmpty()) continue;
                    if (st.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q) || itemId(st).contains(q)) found = i;
                }
                if (found < 0) { ok = false; err = "no hotbar item matches '" + q + "'"; break; }
                previousSlot = selectedSlot(p);
                click(mc.options.keyHotbarSlots[found], 1);
                r.addProperty("slot", found + 1);
                break;
            }
            case "look": case "look_at": {
                if (p == null) { ok = false; err = "not in a world"; break; }
                float yaw, pitch;
                if (op.equals("look_at")) {
                    Vec3 eye = p.getEyePosition();
                    double dx = num(m, "x", 0) - eye.x, dy = num(m, "y", 0) - eye.y, dz = num(m, "z", 0) - eye.z;
                    yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                } else {
                    boolean rel = bool(m, "relative");
                    yaw = (float) num(m, "yaw", 0) + (rel ? p.getYRot() : 0);
                    pitch = (float) num(m, "pitch", 0) + (rel ? p.getXRot() : 0);
                }
                pitch = Math.max(-90f, Math.min(90f, pitch));
                LookAnim a = new LookAnim();
                a.fromYaw = p.getYRot(); a.fromPitch = p.getXRot();
                // take the short way round
                float d = wrap(yaw - a.fromYaw);
                a.toYaw = a.fromYaw + d; a.toPitch = pitch;
                a.total = Math.max(1, (int) Math.round(num(m, "ms", 0) / 50.0));
                look = a;
                break;
            }
            case "message": {
                Component c = Component.literal(str(m, "text", ""));
                if (bool(m, "actionbar")) mc.gui.setOverlayMessage(c, false);
                else mc.gui.getChat().addMessage(Component.literal("§b[MacroForge]§r ").append(c));
                break;
            }
            case "close_screen": {
                if (mc.screen instanceof AbstractContainerScreen<?> && p != null) p.closeContainer();
                else mc.setScreen(null);
                break;
            }
            case "respawn": {
                if (p == null) { ok = false; err = "not in a world"; break; }
                p.respawn();
                mc.setScreen(null);
                break;
            }
            case "release_all": releaseAll(); break;
            case "reset_stats": hits = 0; combo = 0; break;
            case "state": lastStateJson = ""; sendStateIfChanged(mc); break;
            case "entities": {
                JsonArray arr = new JsonArray();
                for (var type : BuiltInRegistries.ENTITY_TYPE) {
                    JsonObject e = new JsonObject();
                    e.addProperty("id", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(type)));
                    e.addProperty("category", type.getCategory().name());
                    arr.add(e);
                }
                r.add("entities", arr);
                break;
            }
            case "items": {
                JsonArray arr = new JsonArray();
                for (var item : BuiltInRegistries.ITEM) arr.add(String.valueOf(BuiltInRegistries.ITEM.getKey(item)));
                r.add("items", arr);
                break;
            }
            case "keys": {
                JsonArray arr = new JsonArray();
                for (KeyMapping k : mc.options.keyMappings) arr.add(k.getName());
                r.add("keys", arr);
                break;
            }
            default: ok = false; err = "unknown op: " + op;
        }
        r.addProperty("ok", ok);
        if (err != null) r.addProperty("error", err);
        server.send(r);
    }
}
