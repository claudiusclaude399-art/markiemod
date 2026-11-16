package dev.markiesdetector.analyzer.gui;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.markiesdetector.glass.Col;
import dev.markiesdetector.glass.Glass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Small shared helpers for the analyzer screens. */
final class Ui {
    private Ui() {}

    static String time(float ticks) {
        float sec = Math.max(0, ticks) / 20f;
        int m = (int) (sec / 60);
        float s = sec - m * 60;
        return String.format("%d:%04.1f", m, s);
    }

    static String duration(int ticks) {
        int sec = ticks / 20;
        return sec >= 60 ? (sec / 60) + "m " + (sec % 60) + "s" : sec + "s";
    }

    static String date(long epoch) {
        return new SimpleDateFormat("d MMM, HH:mm").format(new Date(epoch));
    }

    private static final Map<String, Supplier<SkinTextures>> SKINS = new HashMap<>();

    static SkinTextures skin(String uuid) { return skin(uuid, "", "", ""); }

    /**
     * Skin for a player: from the tab list when they are online, otherwise from the skin property saved with the
     * fight (loaded through the game's skin cache), otherwise the default skin for their UUID.
     */
    static SkinTextures skin(String uuid, String name, String tex, String sig) {
        UUID id;
        try { id = UUID.fromString(uuid); } catch (Exception e) { return DefaultSkinHelper.getSteve(); }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() != null) {
            PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(id);
            if (e != null) return e.getSkinTextures();
        }
        Supplier<SkinTextures> s = SKINS.computeIfAbsent(uuid, k -> {
            GameProfile profile;
            if (tex != null && !tex.isEmpty()) {
                Property prop = sig == null || sig.isEmpty() ? new Property("textures", tex) : new Property("textures", tex, sig);
                profile = new GameProfile(id, name == null || name.isEmpty() ? "player" : name,
                    new PropertyMap(ImmutableMultimap.of("textures", prop)));
            } else if (id.equals(mc.getGameProfile().id())) {
                profile = mc.getGameProfile();
            } else {
                return () -> DefaultSkinHelper.getSkinTextures(id);
            }
            try {
                return mc.getSkinProvider().supplySkinTextures(profile, false);
            } catch (Exception ex) {
                return () -> DefaultSkinHelper.getSkinTextures(id);
            }
        });
        SkinTextures t = s.get();
        return t == null ? DefaultSkinHelper.getSkinTextures(id) : t;
    }

    /** Player head inside a rounded glass frame. */
    static void head(String uuid, float x, float y, int size) { head(skin(uuid), x, y, size); }

    static void head(SkinTextures skin, float x, float y, int size) {
        Glass.shadow(x, y, size, size, size * 0.28f, 5, Col.a(0, 0.35f));
        Glass.round(x - 1, y - 1, size + 2, size + 2, size * 0.28f, Col.a(0xFFFFFF, 0.18f));
        PlayerSkinDrawer.draw(Glass.ctx(), skin, Math.round(x), Math.round(y), size);
        Glass.rim(x - 1, y - 1, size + 2, size + 2, size * 0.28f, Col.a(0xFFFFFF, 0.5f), Col.a(0xFFFFFF, 0.12f));
    }

    /** Tiny line chart of 0..100 values. */
    static void sparkline(float[] v, float x, float y, float w, float h, int rgb) {
        if (v == null || v.length < 2) return;
        float px = x, py = y + h - h * Math.min(100, v[0]) / 100f;
        float seg = w / (v.length - 1f);
        for (int i = 1; i < v.length; i++) {
            float nx = x + w * i / (v.length - 1f), ny = y + h - h * Math.min(100, v[i]) / 100f;
            for (int k = 0; k < 4; k++) {
                float cy = py + (ny - py) * (k + 0.5f) / 4f;
                Glass.rectGradient(px + seg * k / 4f, cy, seg / 4f + 0.01f, y + h - cy, Col.a(rgb, 0.22f), Col.a(rgb, 0f));
            }
            Glass.line(px, py, nx, ny, 1.3f, Col.a(rgb, 1f));
            px = nx;
            py = ny;
        }
    }
}
