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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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

    private static KeyBinding openKey, toggleKey;

    @Override
    public void onInitializeClient() {
        ClickConfig.load();
        MOUSE.load();
        KEYBOARD.load();
        AudioEngine.start();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.clicksounds.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.clicksounds.toggle", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new ClickSoundsScreen(null));
            }
            while (toggleKey.wasPressed()) toggleAll(mc);
        });

        // /clicksounds also opens the menu (queued so the closing chat screen doesn't close it)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("clicksounds").executes(ctx -> {
                MinecraftClient mc = ctx.getSource().getClient();
                mc.send(() -> mc.setScreen(new ClickSoundsScreen(null)));
                return 1;
            })));
    }

    private static void toggleAll(MinecraftClient mc) {
        boolean on = !(ClickConfig.mouseEnabled || ClickConfig.keyboardEnabled);
        ClickConfig.mouseEnabled = on;
        ClickConfig.keyboardEnabled = on;
        ClickConfig.save();
        mc.inGameHud.setOverlayMessage(Text.literal("Click sounds: ")
            .append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED)), false);
    }

    /** Called from MouseMixin on every mouse button press / release. */
    public static void onMouseButton(int button, boolean pressed) {
        if (pressed) {
            if (!recording && ClickConfig.mouseEnabled) MOUSE.press(button, ClickConfig.mouseVolume, ClickConfig.variation);
        } else {
            MOUSE.release(button, !recording && ClickConfig.mouseEnabled && ClickConfig.releaseSounds);
        }
    }

    /** Called from KeyboardMixin on every key press / release (not on hold-repeat). */
    public static void onKey(int key, boolean pressed) {
        if (pressed) {
            if (!recording && ClickConfig.keyboardEnabled) KEYBOARD.press(key, ClickConfig.keyboardVolume, ClickConfig.variation);
        } else {
            KEYBOARD.release(key, !recording && ClickConfig.keyboardEnabled && ClickConfig.releaseSounds);
        }
    }
}
