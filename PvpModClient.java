package dev.pvpmod;

import dev.pvpmod.config.PvpConfig;
import dev.pvpmod.gui.MainScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;

public class PvpModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PvpConfig.load();

        // /pvpmod opens the settings screen (queued so the chat screen doesn't close it again)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("pvpmod").executes(ctx -> {
                MinecraftClient mc = ctx.getSource().getClient();
                mc.send(() -> mc.setScreen(new MainScreen(null)));
                return 1;
            }))
        );
    }
}
