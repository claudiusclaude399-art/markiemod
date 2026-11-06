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
    private static KeyBinding openKey, toggleKey;

    @Override
    public void onInitializeClient() {
        LyricsConfig.load();
        SpotifyClient.start();

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("markielyrics", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.markielyrics.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, cat));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.markielyrics.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, cat));

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
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    LyricsConfig cfg = LyricsConfig.get();
                    cfg.showLyrics = !cfg.showLyrics;
                    LyricsConfig.save();
                    ctx.getSource().sendFeedback(Text.literal("[Markie Lyrics] Lyrics " + (cfg.showLyrics ? "on" : "off")));
                    return 1;
                })))
        );
    }
}
