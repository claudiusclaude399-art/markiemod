package dev.markie.lyrics;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.gui.LyricsScreen;
import dev.markie.lyrics.render.HudRenderer;
import dev.markie.lyrics.render.SkyLyricsRenderer;
import dev.markie.lyrics.spotify.SpotifyAuth;
import dev.markie.lyrics.spotify.SpotifyClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class MarkieLyricsClient implements ClientModInitializer {
    private static KeyBinding openKey, toggleKey, closerKey, fartherKey;

    @Override
    public void onInitializeClient() {
        LyricsConfig.load();
        SpotifyClient.start();

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("markielyrics", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.markielyrics.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, cat));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.markielyrics.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        closerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.markielyrics.closer", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));
        fartherKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.markielyrics.farther", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new LyricsScreen(null));
            }
            while (toggleKey.wasPressed()) {
                LyricsConfig cfg = LyricsConfig.get();
                cfg.showLyrics = !cfg.showLyrics;
                LyricsConfig.save();
                Sounds.play(cfg.showLyrics ? Sounds.TOGGLE_ON : Sounds.TOGGLE_OFF);
                if (mc.player != null) mc.player.sendMessage(Text.literal("Lyrics " + (cfg.showLyrics ? "on" : "off")), true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            int steps = 0;
            while (closerKey.wasPressed()) steps--;
            while (fartherKey.wasPressed()) steps++;
            if (steps != 0) nudgeDistance(mc, steps);
        });

        WorldRenderEvents.AFTER_ENTITIES.register(SkyLyricsRenderer::render);
        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, Identifier.of("markielyrics", "hud"), HudRenderer::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("lyrics")
                .executes(ctx -> {
                    MinecraftClient mc = ctx.getSource().getClient();
                    mc.send(() -> mc.setScreen(new LyricsScreen(null)));
                    return 1;
                })
                .then(ClientCommandManager.literal("connect").executes(ctx -> {
                    SpotifyAuth.startLogin();
                    ctx.getSource().sendFeedback(Text.literal("[Markie Lyrics] " + SpotifyAuth.message));
                    return 1;
                }))
                .then(ClientCommandManager.literal("distance")
                    .then(ClientCommandManager.argument("blocks", com.mojang.brigadier.arguments.FloatArgumentType.floatArg(2f, 128f))
                        .executes(ctx -> {
                            float v = com.mojang.brigadier.arguments.FloatArgumentType.getFloat(ctx, "blocks");
                            LyricsConfig cfg = LyricsConfig.get();
                            if (cfg.where == LyricsConfig.Where.FLOATING) cfg.floatDistance = Math.min(24f, v);
                            else cfg.skyDistance = Math.max(16f, v);
                            LyricsConfig.save();
                            ctx.getSource().sendFeedback(Text.literal("[Markie Lyrics] " + distanceText(cfg)));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    LyricsConfig cfg = LyricsConfig.get();
                    cfg.showLyrics = !cfg.showLyrics;
                    LyricsConfig.save();
                    ctx.getSource().sendFeedback(Text.literal("[Markie Lyrics] Lyrics " + (cfg.showLyrics ? "on" : "off")));
                    return 1;
                })))
        );
    }

    /** Lyrics closer (negative) / farther (positive), for the current mode. */
    private static void nudgeDistance(MinecraftClient mc, int steps) {
        LyricsConfig cfg = LyricsConfig.get();
        switch (cfg.where) {
            case SKY -> cfg.skyDistance = Math.max(16f, Math.min(128f, cfg.skyDistance + steps * 4f));
            case FLOATING -> cfg.floatDistance = Math.max(2f, Math.min(24f, cfg.floatDistance + steps * 0.5f));
            case SCREEN -> cfg.screenY = Math.max(0.05f, Math.min(0.95f, cfg.screenY + steps * 0.05f));
        }
        LyricsConfig.save();
        Sounds.play(Sounds.SLIDER, steps < 0 ? 1.2f : 0.9f);
        if (mc.player != null) mc.player.sendMessage(Text.literal(distanceText(cfg)), true);
    }

    private static String distanceText(LyricsConfig cfg) {
        return switch (cfg.where) {
            case SKY -> "Lyrics distance: " + Math.round(cfg.skyDistance) + " blocks";
            case FLOATING -> String.format("Lyrics distance: %.1f blocks", cfg.floatDistance);
            case SCREEN -> "Lyrics height: " + Math.round(cfg.screenY * 100) + "%";
        };
    }
}
