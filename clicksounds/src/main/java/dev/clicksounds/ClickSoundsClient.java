package dev.clicksounds;

import dev.clicksounds.audio.AudioEngine;
import dev.clicksounds.audio.SampleBank;
import dev.clicksounds.gui.ClickSoundsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClickSoundsClient implements ClientModInitializer {
    public static final String MOD_ID = "clicksounds";
    public static final Logger LOG = LoggerFactory.getLogger("Click Sounds");

    /** 10 recorded mouse clicks. */
    public static final SampleBank MOUSE = new SampleBank("mouse");
    /** 10 recorded key presses (WASD) - used for every key on the keyboard. */
    public static final SampleBank KEYBOARD = new SampleBank("keyboard");

    /** True while the recorder is listening to the mic, so we don't play sounds into it. */
    public static volatile boolean recording = false;

    private static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        ClickConfig.load();
        MOUSE.load();
        KEYBOARD.load();
        AudioEngine.start();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.clicksounds.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K,
            KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new ClickSoundsScreen(null));
            }
        });

        // /clicksounds also opens the menu (queued so the closing chat screen doesn't close it)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("clicksounds").executes(ctx -> {
                MinecraftClient mc = ctx.getSource().getClient();
                mc.send(() -> mc.setScreen(new ClickSoundsScreen(null)));
                return 1;
            })));
    }

    /** Called from MouseMixin on every mouse button press. */
    public static void onMouseButtonPressed(int button) {
        if (recording || !ClickConfig.mouseEnabled) return;
        MOUSE.playRandom(ClickConfig.mouseVolume, ClickConfig.variation);
    }

    /** Called from KeyboardMixin on every key press (not on hold-repeat). */
    public static void onKeyPressed(int key) {
        if (recording || !ClickConfig.keyboardEnabled) return;
        KEYBOARD.playRandom(ClickConfig.keyboardVolume, ClickConfig.variation);
    }
}
