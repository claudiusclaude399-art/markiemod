package dev.markiesdetector;

import dev.markiesdetector.analyzer.AnalyzerClient;
import dev.markiesdetector.config.PvpConfig;
import dev.markiesdetector.gui.MainScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;

public class PvpModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PvpConfig.load();
        AnalyzerClient.init();

        // /markiesdetector settings (and /pvpmod, unless another mod called pvpmod is installed) opens the settings
        // screen, queued so the chat screen doesn't close it again
        boolean ownsPvpmod = !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("pvpmod");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("markiesdetector").then(ClientCommandManager.literal("settings").executes(ctx -> {
                MinecraftClient mc = ctx.getSource().getClient();
                mc.send(() -> mc.setScreen(new MainScreen(null)));
                return 1;
            })));
            if (ownsPvpmod) dispatcher.register(ClientCommandManager.literal("pvpmod").executes(ctx -> {
                MinecraftClient mc = ctx.getSource().getClient();
                mc.send(() -> mc.setScreen(new MainScreen(null)));
                return 1;
            }));
        });
    }
}
