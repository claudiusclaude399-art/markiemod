package dev.pvpmod.analyzer;

import dev.pvpmod.config.PvpConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Watches combat and records 1v1 fights. A fight starts the first time you hit a player or a player hits you,
 * and ends on a death, when they leave, or after a quiet period. Packet hooks come from ClientPlayNetworkHandlerMixin.
 */
public final class FightTracker {
    private FightTracker() {}

    private static final int PREROLL = 60;
    private static final String TOTEM = "totem_of_undying", CRYSTAL = "end_crystal";

    /** The fight currently being recorded (null when idle). */
    public static Live live;
    /** Set when a fight just finished, for the HUD's result animation. */
    public static FightRecord.FightMeta lastFinished;
    public static long lastFinishedAt;
    /** Name of a player to force as the opponent (from /pvpmod analyze). */
    public static String forcedTarget;

    private static final ArrayDeque<PState> meHistory = new ArrayDeque<>();
    private static final Map<UUID, ArrayDeque<PState>> history = new HashMap<>();
    private static ClientWorld lastWorld;

    public static final class Live {
        public final FightRecord rec = new FightRecord();
        public final Analyzer analyzer;
        public final UUID opUuid;
        public int opId;
        int t = 0, lastCombat = 0, absentFor = 0, deathAt = -1;
        int pendingSwings = 0;
        final List<FightEvent> pending = new ArrayList<>();
        // auto totem tracking
        int popTick = -1;
        boolean popSawEmpty = false;
        // crystals placed near the opponent: entity id -> {spawn tick, aim error}
        final Map<Integer, double[]> crystals = new HashMap<>();
        public Flag lastFlag;
        public long lastFlagAt;
        int seenFlags = 0;

        Live(PlayerEntity op) {
            opUuid = op.getUuid();
            opId = op.getId();
            rec.opName = op.getGameProfile().name();
            rec.opUuid = opUuid.toString();
            rec.sens = PvpConfig.get().analyzerSensitivity;
            analyzer = new Analyzer(rec.frames, rec.events, rec.sens);
        }

        public int ticks() { return t; }
    }

    // ------------------------------------------------------------------ packet hooks
    public static void onDamage(EntityDamageS2CPacket p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity me = mc.player;
        if (me == null || mc.world == null || !PvpConfig.get().analyzerEnabled) return;
        Entity victim = mc.world.getEntityById(p.entityId());
        Entity cause = mc.world.getEntityById(p.sourceCauseId());
        boolean melee = p.sourceType().matchesKey(DamageTypes.PLAYER_ATTACK) && p.sourceCauseId() == p.sourceDirectId();

        if (victim == me && cause instanceof PlayerEntity op && op != me) {
            Live l = ensure(op);
            if (l == null || !l.opUuid.equals(op.getUuid())) return;
            l.lastCombat = l.t;
            if (melee) {
                FightEvent e = new FightEvent(l.t, FightEvent.Kind.HIT_ME).at(op.getX(), op.getY(), op.getZ());
                e.b = !lineOfSight(mc.world, op, me, ping(mc, me.getUuid()) + ping(mc, op.getUuid()));
                l.pending.add(e);
            } else {
                l.pending.add(new FightEvent(l.t, FightEvent.Kind.BLAST_ME).at(me.getX(), me.getY(), me.getZ()));
            }
        } else if (cause == me && victim instanceof PlayerEntity op && op != me) {
            Live l = ensure(op);
            if (l == null || !l.opUuid.equals(op.getUuid())) return;
            l.lastCombat = l.t;
            if (melee) {
                FightEvent e = new FightEvent(l.t, FightEvent.Kind.HIT_OP).at(op.getX(), op.getY(), op.getZ());
                e.b = blockedBehind(mc.world, op, me);
                l.pending.add(e);
            } else {
                l.pending.add(new FightEvent(l.t, FightEvent.Kind.BLAST_OP).at(op.getX(), op.getY(), op.getZ()));
            }
        } else if (live != null && victim != null && victim.getId() == live.opId) {
            live.pending.add(new FightEvent(live.t, FightEvent.Kind.BLAST_OP).at(victim.getX(), victim.getY(), victim.getZ()));
        }
    }

    public static void onAnimation(EntityAnimationS2CPacket p) {
        if (live == null) return;
        int a = p.getAnimationId();
        if (p.getEntityId() == live.opId && (a == EntityAnimationS2CPacket.SWING_MAIN_HAND || a == EntityAnimationS2CPacket.SWING_OFF_HAND)) {
            live.pendingSwings++;
        }
    }

    public static void onStatus(EntityStatusS2CPacket p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (live == null || mc.world == null || mc.player == null) return;
        Entity e = p.getEntity(mc.world);
        if (e == null) return;
        byte s = p.getStatus();
        boolean isOp = e.getId() == live.opId, isMe = e == mc.player;
        if (s == 35) {   // totem pop
            if (isOp) {
                live.pending.add(new FightEvent(live.t, FightEvent.Kind.POP_OP).at(e.getX(), e.getY(), e.getZ()));
                live.lastCombat = live.t;
                PlayerEntity op = (PlayerEntity) e;
                int held = (isTotem(op.getMainHandStack()) ? 1 : 0) + (isTotem(op.getOffHandStack()) ? 1 : 0);
                if (held <= 1) { live.popTick = live.t; live.popSawEmpty = false; }
            } else if (isMe) {
                live.pending.add(new FightEvent(live.t, FightEvent.Kind.POP_ME).at(e.getX(), e.getY(), e.getZ()));
                live.lastCombat = live.t;
            }
        } else if (s == 3) {  // death
            if (isOp && live.deathAt < 0) {
                live.pending.add(new FightEvent(live.t, FightEvent.Kind.DEATH_OP).at(e.getX(), e.getY(), e.getZ()));
                live.rec.result = "WIN";
                live.deathAt = live.t;
            }
        }
    }

    public static void onEntityLoad(Entity e) {
        if (live == null || !(e instanceof EndCrystalEntity)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        Entity op = mc.world.getEntityById(live.opId);
        if (!(op instanceof PlayerEntity p)) return;
        double dOp = p.squaredDistanceTo(e), dMe = mc.player.squaredDistanceTo(e);
        boolean holding = p.getMainHandStack().isOf(net.minecraft.item.Items.END_CRYSTAL) || p.getOffHandStack().isOf(net.minecraft.item.Items.END_CRYSTAL);
        if (dOp > 36 || dOp > dMe || !holding) return;
        PState s = snapshot(p, mc.world, true);
        double err = Analyzer.angleTo(s, e.getX(), e.getY(), e.getZ());
        live.crystals.put(e.getId(), new double[]{live.t, err});
    }

    public static void onEntityUnload(Entity e) {
        if (live == null || !(e instanceof EndCrystalEntity)) return;
        double[] c = live.crystals.remove(e.getId());
        if (c == null) return;
        FightEvent ev = new FightEvent(live.t, FightEvent.Kind.CRYSTAL_OP).at(e.getX(), e.getY(), e.getZ());
        ev.v = live.t - c[0];
        ev.d = c[1];
        live.pending.add(ev);
    }

    // ------------------------------------------------------------------ lifecycle
    private static Live ensure(PlayerEntity op) {
        if (live != null) return live;
        if (forcedTarget != null && !forcedTarget.equalsIgnoreCase(op.getGameProfile().name())) return null;
        start(op);
        return live;
    }

    private static void start(PlayerEntity op) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Live l = new Live(op);
        l.rec.start = System.currentTimeMillis();
        l.rec.id = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(l.rec.start)) + "-" + op.getGameProfile().name().replaceAll("[^A-Za-z0-9_]", "");
        l.rec.meUuid = mc.player.getUuid().toString();
        l.rec.meName = mc.player.getGameProfile().name();
        String[] meTex = textures(mc, mc.player), opTex = textures(mc, op);
        l.rec.meTex = meTex[0]; l.rec.meSig = meTex[1];
        l.rec.opTex = opTex[0]; l.rec.opSig = opTex[1];
        l.rec.server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : (mc.isInSingleplayer() ? "Singleplayer" : "");
        l.rec.dim = mc.world.getRegistryKey().getValue().toString();

        // pre-roll: the moments before the first hit
        ArrayDeque<PState> oh = history.get(op.getUuid());
        List<PState> mine = new ArrayList<>(meHistory), theirs = oh == null ? List.of() : new ArrayList<>(oh);
        int n = Math.min(mine.size(), theirs.size());
        for (int i = 0; i < n; i++) {
            PState m = mine.get(mine.size() - n + i), o = theirs.get(theirs.size() - n + i).copy();
            o.s = 0;
            l.rec.frames.add(new Frame(l.t++, m, o, ping(mc, mc.player.getUuid()), ping(mc, op.getUuid())));
        }
        l.rec.preroll = n;
        l.lastCombat = l.t;
        live = l;
    }

    public static void tick(MinecraftClient mc) {
        ClientWorld world = mc.world;
        ClientPlayerEntity me = mc.player;
        if (world != lastWorld) {
            if (live != null) end(live.rec.result.equals("END") ? "LEFT" : live.rec.result);
            meHistory.clear();
            history.clear();
            lastWorld = world;
        }
        if (world == null || me == null) return;
        if (!PvpConfig.get().analyzerEnabled) {
            if (live != null) end("END");
            return;
        }

        // keep a short history of everyone nearby for the pre-roll
        PState meNow = snapshot(me, world, false);
        push(meHistory, meNow);
        for (PlayerEntity p : world.getPlayers()) {
            if (p == me || p.squaredDistanceTo(me) > 48 * 48) continue;
            push(history.computeIfAbsent(p.getUuid(), k -> new ArrayDeque<>()), snapshot(p, world, true));
        }
        history.keySet().removeIf(u -> world.getPlayerByUuid(u) == null);

        Live l = live;
        if (l == null) return;
        PlayerEntity op = world.getPlayerByUuid(l.opUuid);
        PState os;
        if (op == null || op.isRemoved()) {
            l.absentFor++;
            os = l.rec.frames.isEmpty() ? new PState() : l.rec.frames.get(l.rec.frames.size() - 1).op.copy();
            os.f |= PState.ABSENT;
            os.s = 0;
        } else {
            l.absentFor = 0;
            l.opId = op.getId();
            os = snapshot(op, world, true);
            os.s = l.pendingSwings;
            trackTotem(l, os);
        }
        l.pendingSwings = 0;
        l.rec.frames.add(new Frame(l.t, meNow, os, ping(mc, me.getUuid()), ping(mc, l.opUuid)));
        for (FightEvent e : l.pending) e.t = l.t;
        l.rec.events.addAll(l.pending);
        l.pending.clear();
        l.analyzer.step();
        if (l.analyzer.flags.size() > l.seenFlags) {
            l.lastFlag = l.analyzer.flags.get(l.analyzer.flags.size() - 1);
            l.lastFlagAt = System.currentTimeMillis();
            l.seenFlags = l.analyzer.flags.size();
        }
        l.t++;

        // end conditions
        if (me.isDead() || me.getHealth() <= 0) {
            if (l.deathAt < 0) {
                l.rec.events.add(new FightEvent(l.t, FightEvent.Kind.DEATH_ME).at(me.getX(), me.getY(), me.getZ()));
                l.rec.result = "LOSS";
                l.deathAt = l.t;
            }
        }
        if (l.deathAt >= 0 && l.t - l.deathAt > 30) end(l.rec.result);
        else if (l.absentFor > 100) end("LEFT");
        else if (l.t - l.lastCombat > PvpConfig.get().analyzerTimeout * 20) end("END");
        else if (l.t > 20 * 60 * 15) end("END");
    }

    private static void trackTotem(Live l, PState os) {
        if (l.popTick < 0) return;
        boolean has = TOTEM.equals(os.m) || TOTEM.equals(os.o);
        if (!has) l.popSawEmpty = true;
        int since = l.t - l.popTick;
        if (has && l.popSawEmpty) {
            FightEvent e = new FightEvent(l.t, FightEvent.Kind.REFILL_OP).at(os.x, os.y, os.z);
            e.v = since;
            l.pending.add(e);
            l.popTick = -1;
        } else if (has && since >= 10) {
            // the hand never showed empty: swapped within the same server tick as the pop
            FightEvent e = new FightEvent(l.t, FightEvent.Kind.REFILL_OP).at(os.x, os.y, os.z);
            e.v = 0;
            l.pending.add(e);
            l.popTick = -1;
        } else if (since > 200) {
            l.popTick = -1;
        }
    }

    /** Stop recording, analyse and save. */
    public static void end(String result) {
        Live l = live;
        live = null;
        if (l == null) return;
        l.rec.result = result;
        l.analyzer.step();
        l.analyzer.finish();
        if (l.rec.frames.size() - l.rec.preroll < 20) return;   // too short to be a fight
        FightRecord.FightMeta meta = l.rec.meta(l.analyzer);
        lastFinished = meta;
        lastFinishedAt = System.currentTimeMillis();
        if (PvpConfig.get().analyzerSave) FightStore.save(l.rec, meta);
        else {
            FightStore.list();
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && PvpConfig.get().analyzerChat) {
            int sc = Math.round(meta.score);
            Formatting col = sc < 20 ? Formatting.GREEN : sc < 45 ? Formatting.YELLOW : sc < 75 ? Formatting.GOLD : Formatting.RED;
            mc.player.sendMessage(Text.literal("[Fight Analyzer] ").formatted(Formatting.AQUA)
                .append(Text.literal("vs " + meta.opName + ": ").formatted(Formatting.WHITE))
                .append(Text.literal(Analyzer.verdict(meta.score) + " (" + sc + "%)").formatted(col))
                .append(Text.literal(meta.flags > 0 ? "  " + meta.flags + " flags" : "").formatted(Formatting.GRAY))
                .append(Text.literal("  - /pvpmod fights to review").formatted(Formatting.DARK_GRAY)), false);
        }
    }

    // ------------------------------------------------------------------ helpers
    private static void push(ArrayDeque<PState> q, PState s) {
        q.addLast(s);
        while (q.size() > PREROLL) q.pollFirst();
    }

    private static int ping(MinecraftClient mc, UUID id) {
        if (mc.getNetworkHandler() == null) return 0;
        PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(id);
        return e == null ? 0 : Math.max(0, Math.min(1000, e.getLatency()));
    }

    /** The signed skin property of a player ({value, signature}), from the tab list or their profile. */
    private static String[] textures(MinecraftClient mc, PlayerEntity p) {
        com.mojang.authlib.GameProfile profile = p.getGameProfile();
        if (mc.getNetworkHandler() != null) {
            PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(p.getUuid());
            if (e != null) profile = e.getProfile();
        }
        if (p == mc.player && profile.properties().get("textures").isEmpty()) profile = mc.getGameProfile();
        for (com.mojang.authlib.properties.Property prop : profile.properties().get("textures")) {
            return new String[]{prop.value(), prop.signature() == null ? "" : prop.signature()};
        }
        return new String[]{"", ""};
    }

    private static boolean isTotem(ItemStack s) { return s.isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING); }

    private static String id(ItemStack s) {
        return s.isEmpty() ? "" : Registries.ITEM.getId(s.getItem()).getPath();
    }

    /** Capture a player. For other players the server position (interpolation target) is used, not the smoothed one. */
    public static PState snapshot(PlayerEntity p, ClientWorld world, boolean remote) {
        PState s = new PState();
        Vec3d pos = p.getEntityPos();
        float yaw = p.getHeadYaw(), pitch = p.getPitch();
        if (remote && p.getInterpolator() != null && p.getInterpolator().isInterpolating()) {
            pos = p.getInterpolator().getLerpedPos();
            pitch = p.getInterpolator().getLerpedPitch();
        }
        s.x = pos.x; s.y = pos.y; s.z = pos.z;
        s.yw = remote ? yaw : p.getYaw();
        s.pt = pitch;
        s.hp = p.getHealth();
        s.ab = p.getAbsorptionAmount();
        s.e = p.getStandingEyeHeight();
        var dims = p.getDimensions(p.getPose());
        s.h = dims.height();
        s.w = dims.width();
        int f = 0;
        if (p.isOnGround()) f |= PState.GROUND;
        if (p.isSprinting()) f |= PState.SPRINT;
        if (p.isSneaking() || p.getPose() == EntityPose.CROUCHING) f |= PState.SNEAK;
        if (p.isGliding()) f |= PState.GLIDE;
        if (p.isTouchingWater() || p.isInLava() || inSlowBlock(world, p)) f |= PState.FLUID;
        if (onSlipperyBlock(world, p)) f |= PState.SLIPPERY;
        if (p.isClimbing()) f |= PState.CLIMB;
        if (p.isUsingItem()) f |= PState.USING;
        if (p.hasVehicle()) f |= PState.VEHICLE;
        if (remote && supported(world, p, pos)) f |= PState.SUPPORTED;
        if (isCreativeLike(p)) f |= PState.CREATIVE;
        s.f = f;
        s.m = id(p.getMainHandStack());
        s.o = id(p.getOffHandStack());
        return s;
    }

    private static boolean isCreativeLike(PlayerEntity p) {
        if (p.isCreative() || p.isSpectator()) return true;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return false;
        PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(p.getUuid());
        return e != null && (e.getGameMode() == GameMode.CREATIVE || e.getGameMode() == GameMode.SPECTATOR);
    }

    private static boolean supported(ClientWorld world, PlayerEntity p, Vec3d pos) {
        double hw = p.getWidth() / 2;
        Box below = new Box(pos.x - hw, pos.y - 0.25, pos.z - hw, pos.x + hw, pos.y + 0.05, pos.z + hw);
        if (world.getBlockCollisions(p, below).iterator().hasNext()) return true;
        // standing on boats, shulkers etc.
        return !world.getOtherEntities(p, below.expand(0.05), e -> e.isCollidable(p)).isEmpty();
    }

    /** Ice (and soul sand / soil for Soul Speed) let players legitimately move much faster. */
    private static boolean onSlipperyBlock(ClientWorld world, LivingEntity p) {
        BlockPos below = BlockPos.ofFloored(p.getX(), p.getY() - 0.3, p.getZ());
        for (BlockPos bp : new BlockPos[]{below, below.down()}) {
            BlockState st = world.getBlockState(bp);
            if (st.isOf(Blocks.ICE) || st.isOf(Blocks.PACKED_ICE) || st.isOf(Blocks.BLUE_ICE) || st.isOf(Blocks.FROSTED_ICE)
                || st.isOf(Blocks.SOUL_SAND) || st.isOf(Blocks.SOUL_SOIL) || st.isOf(Blocks.SLIME_BLOCK)) return true;
        }
        return false;
    }

    private static boolean inSlowBlock(ClientWorld world, LivingEntity p) {
        BlockPos feet = p.getBlockPos();
        for (BlockPos bp : new BlockPos[]{feet, feet.up()}) {
            BlockState st = world.getBlockState(bp);
            if (st.isOf(Blocks.COBWEB) || st.isOf(Blocks.POWDER_SNOW) || st.isOf(Blocks.SWEET_BERRY_BUSH)
                || st.isOf(Blocks.HONEY_BLOCK) || st.isOf(Blocks.BUBBLE_COLUMN)) return true;
        }
        return false;
    }

    /**
     * Could {@code from} have seen any part of {@code to}'s hitbox? Checked against where {@code to} was over the last
     * (ping + jitter) ticks, because that is what their screen showed, using block outlines like a real crosshair.
     */
    private static boolean lineOfSight(ClientWorld world, PlayerEntity from, PlayerEntity to, int pingMs) {
        Vec3d eye = from.getInterpolator() != null && from.getInterpolator().isInterpolating()
            ? from.getInterpolator().getLerpedPos().add(0, from.getStandingEyeHeight(), 0) : from.getEyePos();
        List<Box> boxes = new ArrayList<>();
        boxes.add(to.getBoundingBox());
        int k = Math.min(meHistory.size(), (int) Math.ceil(pingMs * 1.2 / 50.0) + 4);
        Iterator<PState> it = meHistory.descendingIterator();
        for (int i = 0; i < k && it.hasNext(); i++) {
            PState p = it.next();
            double hw = p.w / 2;
            boxes.add(new Box(p.x - hw, p.y, p.z - hw, p.x + hw, p.y + p.h, p.z + hw));
        }
        for (Box b : boxes) {
            Vec3d c = b.getCenter();
            Vec3d[] targets = {
                c,
                new Vec3d(c.x, b.maxY - 0.1, c.z),
                new Vec3d(c.x, b.minY + 0.1, c.z),
                new Vec3d(b.minX + 0.05, c.y, b.minZ + 0.05),
                new Vec3d(b.maxX - 0.05, c.y, b.maxZ - 0.05),
                new Vec3d(Math.max(b.minX, Math.min(eye.x, b.maxX)), Math.max(b.minY, Math.min(eye.y, b.maxY)), Math.max(b.minZ, Math.min(eye.z, b.maxZ)))
            };
            for (Vec3d t : targets) {
                HitResult hr = world.raycast(new RaycastContext(eye, t, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, from));
                if (hr.getType() == HitResult.Type.MISS) return true;
            }
        }
        return false;
    }

    /** Is there a wall right behind the opponent (relative to me), so knockback can't move them? */
    private static boolean blockedBehind(ClientWorld world, PlayerEntity op, PlayerEntity me) {
        double dx = op.getX() - me.getX(), dz = op.getZ() - me.getZ(), len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return true;
        Box moved = op.getBoundingBox().offset(dx / len * 0.45, 0.05, dz / len * 0.45);
        Iterator<?> it = world.getBlockCollisions(op, moved).iterator();
        return it.hasNext();
    }
}
