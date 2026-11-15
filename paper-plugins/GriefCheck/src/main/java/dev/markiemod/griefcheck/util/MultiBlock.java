package dev.markiemod.griefcheck.util;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;

/** Doors, tall plants and beds are two blocks; this finds the other half. */
public final class MultiBlock {

    public record Part(Block block, BlockData data) {
    }

    private MultiBlock() {
    }

    public static Part companion(Block block, BlockData data) {
        if (data instanceof Bed bed) {
            BlockFace dir = bed.getPart() == Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace();
            Bed other = (Bed) bed.clone();
            other.setPart(bed.getPart() == Bed.Part.FOOT ? Bed.Part.HEAD : Bed.Part.FOOT);
            return new Part(block.getRelative(dir), other);
        }
        if (data instanceof Bisected half && !(data instanceof org.bukkit.block.data.type.Stairs)
                && !(data instanceof org.bukkit.block.data.type.TrapDoor)) {
            boolean bottom = half.getHalf() == Bisected.Half.BOTTOM;
            Bisected other = (Bisected) half.clone();
            other.setHalf(bottom ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
            return new Part(block.getRelative(bottom ? BlockFace.UP : BlockFace.DOWN), other);
        }
        return null;
    }
}
