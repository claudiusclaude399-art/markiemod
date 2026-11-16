package dev.markiesdetector.analyzer.gui;

import com.mojang.authlib.GameProfile;
import dev.markiesdetector.analyzer.PState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws recorded players with the game's own player renderer: real skins, armor (with enchantment glint), held
 * items, walking, attack swings, sneaking, blocking / bow / crossbow / trident poses and the red hurt flash.
 * A hidden client-side player per fighter is dressed in the recorded gear, the game builds its render state,
 * and the pose is then set from the recording. Needs a loaded world; otherwise callers fall back to box models.
 */
final class RealPlayers {
    private RealPlayers() {}

    private static final Map<String, OtherClientPlayerEntity> ACTORS = new HashMap<>();
    private static final Map<String, ItemStack> STACKS = new HashMap<>();
    private static ClientWorld actorWorld;
    private static boolean broken = false;
    /** Direction of the on-screen lean correction (GUI y points down, so this is checked visually). */
    static float ROLL_SIGN = 1f;

    static boolean available() {
        return !broken && MinecraftClient.getInstance().world != null;
    }

    private static OtherClientPlayerEntity actor(String uuid, String name) {
        ClientWorld w = MinecraftClient.getInstance().world;
        if (w != actorWorld) { ACTORS.clear(); actorWorld = w; }
        return ACTORS.computeIfAbsent(uuid, k -> {
            UUID id;
            try { id = UUID.fromString(k); } catch (Exception e) { id = UUID.nameUUIDFromBytes(k.getBytes()); }
            return new OtherClientPlayerEntity(w, new GameProfile(id, name == null || name.isEmpty() ? "player" : name));
        });
    }

    private static ItemStack stack(String id, boolean glint) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        return STACKS.computeIfAbsent(id + (glint ? "*" : ""), k -> {
            Identifier ident = id.contains(":") ? Identifier.tryParse(id) : Identifier.ofVanilla(id);
            Item item = ident == null ? Items.AIR : Registries.ITEM.get(ident);
            ItemStack s = new ItemStack(item);
            if (glint && !s.isEmpty()) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            return s;
        }).copy();
    }

    /**
     * Draws one player whose feet and head project to the given screen points. {@code viewYaw}/{@code viewPitch} are
     * the direction the camera sees this player from; {@code roll} is how far their vertical axis leans on screen
     * (perspective), so the flat game model lines up with the 3D scene. {@code phase}/{@code amt} drive walking,
     * {@code swing} is 0..1 or -1.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void draw(DrawContext ctx, PState p, SkinTextures skin, String uuid, String name,
                     float feetX, float feetY, float headX, float headY, float viewYaw, float viewPitch,
                     float phase, float amt, float swing, boolean hurt) {
        float h = (float) Math.hypot(headX - feetX, headY - feetY);
        float pxPerBlock = h / Math.max(0.5f, p.h > 0 ? p.h : 1.8f);
        float roll = (float) Math.atan2(headX - feetX, feetY - headY);   // 0 = upright on screen
        if (pxPerBlock < 1.5f || p == null) return;
        try {
            OtherClientPlayerEntity e = actor(uuid, name);
            e.equipStack(EquipmentSlot.MAINHAND, stack(p.m, (p.gl & 1) != 0));
            e.equipStack(EquipmentSlot.OFFHAND, stack(p.o, (p.gl & 2) != 0));
            e.equipStack(EquipmentSlot.HEAD, stack(p.hd, (p.gl & 4) != 0));
            e.equipStack(EquipmentSlot.CHEST, stack(p.ch, (p.gl & 8) != 0));
            e.equipStack(EquipmentSlot.LEGS, stack(p.lg, (p.gl & 16) != 0));
            e.equipStack(EquipmentSlot.FEET, stack(p.ft, (p.gl & 32) != 0));
            boolean sneak = p.has(PState.SNEAK);
            e.setSneaking(sneak);
            e.setPose(sneak ? EntityPose.CROUCHING : EntityPose.STANDING);

            EntityRenderer renderer = MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(e);
            EntityRenderState st = renderer.getAndUpdateRenderState(e, 1.0f);
            if (!(st instanceof PlayerEntityRenderState ps)) return;

            // pose from the recording
            ps.skinTextures = skin;
            ps.playerName = null;
            ps.displayName = null;
            ps.nameLabelPos = null;
            ps.shadowPieces.clear();
            ps.shadowRadius = 0;
            ps.bodyYaw = PState.wrap(p.yw - viewYaw);
            ps.relativeHeadYaw = 0;
            ps.pitch = p.pt;
            ps.limbSwingAnimationProgress = phase;
            ps.limbSwingAmplitude = Math.max(0, Math.min(1, amt));
            ps.handSwingProgress = swing >= 0 ? swing : 0;
            ps.hurt = hurt;
            ps.isInSneakingPose = sneak;
            ps.sneaking = sneak;
            ps.pose = sneak ? EntityPose.CROUCHING : EntityPose.STANDING;
            ps.mainArm = Arm.RIGHT;

            // item use: blocking, bows, crossbows, tridents; eating/drinking just holds the item up
            boolean using = p.has(PState.USING);
            ps.isUsingItem = using;
            boolean off = (p.f & PState.USE_OFFHAND) != 0;
            ps.activeHand = off ? Hand.OFF_HAND : Hand.MAIN_HAND;
            ps.itemUseTime = using ? 10 : 0;
            if (using) {
                String held = off ? p.o : p.m;
                BipedEntityModel.ArmPose pose = switch (held == null ? "" : held) {
                    case "shield" -> BipedEntityModel.ArmPose.BLOCK;
                    case "bow" -> BipedEntityModel.ArmPose.BOW_AND_ARROW;
                    case "crossbow" -> BipedEntityModel.ArmPose.CROSSBOW_CHARGE;
                    case "trident" -> BipedEntityModel.ArmPose.THROW_TRIDENT;
                    case "spyglass" -> BipedEntityModel.ArmPose.SPYGLASS;
                    default -> null;
                };
                if (pose != null) {
                    if (off) ps.leftArmPose = pose; else ps.rightArmPose = pose;
                }
            }

            // orient the model the way the 3D camera sees it: looked at from viewYaw/viewPitch, leaning by roll
            float pitchRad = (float) Math.toRadians(viewPitch);
            Quaternionf view = new Quaternionf().rotateX(pitchRad);
            Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI + ROLL_SIGN * roll).mul(view);
            float cx = (feetX + headX) / 2f, cy = (feetY + headY) / 2f;
            int half = Math.max(4, Math.round(h * 0.9f));
            Vector3f translation = new Vector3f(0, ps.height / 2f + 0.0625f, 0);
            ctx.addEntity(st, pxPerBlock, translation, rotation, view,
                Math.round(cx - half), Math.round(cy - half), Math.round(cx + half), Math.round(cy + half));
        } catch (Throwable t) {
            broken = true;   // fall back to box models for the rest of the session
            System.err.println("[MarkiesDetector] game player models unavailable, using fallback: " + t);
        }
    }
}
