package dev.markiesdetector.analyzer;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.markiesdetector.analyzer.gui.AnalyzerHomeScreen;
import dev.markiesdetector.analyzer.gui.AnalyzerHud;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Registers the fight analyzer: tick recorder, entity hooks, HUD island, keybind and commands. */
public final class AnalyzerClient {
    private AnalyzerClient() {}

    private static KeyBinding openKey;

    public static void init() {
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("markiesdetector", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.markiesdetector.analyzer", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                FightTracker.tick(mc);
            } catch (Throwable t) {
                System.err.println("[MarkiesDetector] analyzer tick: " + t);
            }
            while (openKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new AnalyzerHomeScreen(null));
            }
        });
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> FightTracker.onEntityLoad(entity));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> FightTracker.onEntityUnload(entity));
        HudElementRegistry.addLast(Identifier.of("markiesdetector", "analyzer_island"), (ctx, tick) -> AnalyzerHud.render(ctx));

        // /markiesdetector opens the detector; /pvpmod (the settings menu) gets the same sub-commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(commands("markiesdetector").executes(ctx -> {
                MinecraftClient mc = ctx.getSource().getClient();
                mc.send(() -> mc.setScreen(new AnalyzerHomeScreen(null)));
                return 1;
            }));
            // leave /pvpmod alone when another mod called pvpmod is installed
            if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("pvpmod")) dispatcher.register(commands("pvpmod"));
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> commands(String root) {
        return ClientCommandManager.literal(root)
                .then(ClientCommandManager.literal("fights").executes(ctx -> {
                    MinecraftClient mc = ctx.getSource().getClient();
                    mc.send(() -> mc.setScreen(new AnalyzerHomeScreen(null)));
                    return 1;
                }))
                .then(ClientCommandManager.literal("analyze")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word()).executes(ctx -> {
                        FightTracker.forcedTarget = StringArgumentType.getString(ctx, "player");
                        ctx.getSource().sendFeedback(Text.literal("[Markie's Detector] Only fights with " + FightTracker.forcedTarget + " will be recorded. /markiesdetector analyze off to undo."));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(ctx -> {
                        FightTracker.forcedTarget = null;
                        ctx.getSource().sendFeedback(Text.literal("[Markie's Detector] Recording fights with anyone again."));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("demo4").executes(ctx -> {
                    MinecraftClient mc = ctx.getSource().getClient();
                    mc.send(() -> mc.setScreen(new dev.markiesdetector.analyzer.gui.FightReportScreen(new AnalyzerHomeScreen(null), DemoFight.saveGroupDemo())));
                    return 1;
                }))
                .then(ClientCommandManager.literal("demo").executes(ctx -> {
                    MinecraftClient mc = ctx.getSource().getClient();
                    mc.send(() -> mc.setScreen(new dev.markiesdetector.analyzer.gui.FightReportScreen(new AnalyzerHomeScreen(null), DemoFight.saveDemo())));
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    FightTracker.end("END");
                    return 1;
                }));
    }
}
