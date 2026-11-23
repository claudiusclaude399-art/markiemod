package dev.clicksounds;

import dev.clicksounds.audio.AudioEngine;
import dev.clicksounds.audio.Packs;
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

    /** Mouse sounds from the active mouse clickpack. */
    public static final SampleBank MOUSE = new SampleBank(Packs.MOUSE);
    /** Key sounds (recorded on WASD) from the active keyboard clickpack - used for every key. */
    public static final SampleBank KEYBOARD = new SampleBank(Packs.KEYBOARD);

    /** True while the recorder is listening to the mic, so we don't play sounds into it. */
    public static volatile boolean recording = false;

    private static KeyBinding openKey, toggleKey, nextMousePack, nextKeyboardPack;

    @Override
    public void onInitializeClient() {
        ClickConfig.load();
        Packs.setup();
        loadPacks();
        AudioEngine.start();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.clicksounds.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.clicksounds.toggle", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), category));

        nextMousePack = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.clicksounds.next_mouse_pack", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), category));
        nextKeyboardPack = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.clicksounds.next_keyboard_pack", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (nextMousePack.wasPressed()) announce(mc, true, cyclePack(true));
            while (nextKeyboardPack.wasPressed()) announce(mc, false, cyclePack(false));
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new ClickSoundsScreen(null));
            }
            while (toggleKey.wasPressed()) toggleAll(mc);
        });

        // /clicksounds opens the menu (queued so the closing chat screen doesn't close it);
        // /clicksounds pack <mouse|keyboard> <name> switches clickpack
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("clicksounds")
                .executes(ctx -> {
                    MinecraftClient mc = ctx.getSource().getClient();
                    mc.send(() -> mc.setScreen(new ClickSoundsScreen(null)));
                    return 1;
                })
                .then(ClientCommandManager.literal("pack")
                    .then(packCommand("mouse", true))
                    .then(packCommand("keyboard", false)))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>
            packCommand(String device, boolean mouse) {
        return ClientCommandManager.literal(device)
            .executes(ctx -> {
                SampleBank bank = mouse ? MOUSE : KEYBOARD;
                ctx.getSource().sendFeedback(Text.literal(device + " clickpack: " + bank.pack() + " (" + bank.summary()
                    + "). Packs: " + String.join(", ", Packs.list())));
                return 1;
            })
            .then(ClientCommandManager.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                .suggests((ctx, b) -> {
                    String typed = b.getRemaining().toLowerCase(java.util.Locale.ROOT);
                    for (String p : Packs.list()) if (p.toLowerCase(java.util.Locale.ROOT).startsWith(typed)) b.suggest(p);
                    return b.buildFuture();
                })
                .executes(ctx -> {
                    String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name");
                    String match = null;
                    for (String p : Packs.list()) if (p.equalsIgnoreCase(name)) match = p;
                    if (match == null) {
                        ctx.getSource().sendError(Text.literal("No clickpack called '" + name + "'. Packs: " + String.join(", ", Packs.list())));
                        return 0;
                    }
                    setPack(mouse, match);
                    SampleBank bank = mouse ? MOUSE : KEYBOARD;
                    ctx.getSource().sendFeedback(Text.literal(device + " clickpack set to " + bank.pack() + " (" + bank.summary() + ")"));
                    return 1;
                }));
    }

    /** Switches a device to another clickpack and saves the choice. */
    public static void setPack(boolean mouse, String pack) {
        SampleBank bank = mouse ? MOUSE : KEYBOARD;
        bank.load(pack);
        if (mouse) ClickConfig.mousePack = bank.pack(); else ClickConfig.keyboardPack = bank.pack();
        ClickConfig.save();
    }

    /** Moves a device to the next pack folder (alphabetically, wrapping around). @return the new pack */
    public static String cyclePack(boolean mouse) {
        SampleBank bank = mouse ? MOUSE : KEYBOARD;
        java.util.List<String> packs = Packs.list();
        if (packs.isEmpty()) return bank.pack();
        setPack(mouse, packs.get((packs.indexOf(bank.pack()) + 1) % packs.size()));
        return bank.pack();
    }

    private static void announce(MinecraftClient mc, boolean mouse, String pack) {
        SampleBank bank = mouse ? MOUSE : KEYBOARD;
        mc.inGameHud.setOverlayMessage(Text.literal((mouse ? "Mouse" : "Keyboard") + " clickpack: ")
            .append(Text.literal(pack).formatted(Formatting.AQUA))
            .append(Text.literal(" (" + bank.summary() + ")").formatted(Formatting.GRAY)), false);
    }

    /** (Re)loads both active packs from disk. */
    public static void loadPacks() {
        MOUSE.load(ClickConfig.mousePack);
        KEYBOARD.load(ClickConfig.keyboardPack);
        ClickConfig.mousePack = MOUSE.pack();
        ClickConfig.keyboardPack = KEYBOARD.pack();
        ClickConfig.save();
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
