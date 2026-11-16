package dev.markiesdetector.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the 3D replay submit true four-corner quads instead of parallelograms. */
@Mixin(DrawContext.class)
public interface DrawContextAccessor {
    @Accessor("state")
    GuiRenderState markiesdetector$getState();
}
