package com.macroforge.bridge.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the bridge register a real "click" on any key binding (attack, use, hotbar…). */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("clickCount")
    int macroforge$getClickCount();

    @Accessor("clickCount")
    void macroforge$setClickCount(int value);
}
