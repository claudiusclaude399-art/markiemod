package dev.markiesdetector.analyzer;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

/** Reads the blocks around a fight from the client world into a {@link WorldSnapshot}. */
public final class WorldCapture {
    private WorldCapture() {}

    /** Capture every cell within ~2 cells of the given position that isn't saved yet. */
    public static void ensure(WorldSnapshot snap, ClientWorld world, double x, double y, double z) {
        if (snap.full()) return;
        int cx = (int) Math.floor(x) >> 3, cz = (int) Math.floor(z) >> 3;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (snap.claim(cx + dx, cz + dz)) captureCell(snap, world, (cx + dx) * WorldSnapshot.CELL, (int) Math.floor(y), (cz + dz) * WorldSnapshot.CELL);
            }
        }
    }

    private static void captureCell(WorldSnapshot snap, ClientWorld world, int x0, int yMid, int z0) {
        BlockPos.Mutable p = new BlockPos.Mutable(), n = new BlockPos.Mutable();
        int[][] dirs = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (int x = x0; x < x0 + WorldSnapshot.CELL; x++) {
            for (int z = z0; z < z0 + WorldSnapshot.CELL; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = yMid - 7; y <= yMid + 9; y++) {
                    p.set(x, y, z);
                    BlockState st = world.getBlockState(p);
                    if (st.isAir()) continue;
                    FluidState fl = st.getFluidState();
                    boolean water = !fl.isEmpty() && fl.isIn(FluidTags.WATER), lava = !fl.isEmpty() && fl.isIn(FluidTags.LAVA);
                    VoxelShape shape = st.getCollisionShape(world, p);
                    if (shape.isEmpty() && !water && !lava) continue;   // grass, flowers, torches...
                    MapColor mc = st.getMapColor(world, p);
                    int rgb = water ? 0x3F76E4 : lava ? 0xFF6A00 : mc == null || mc == MapColor.CLEAR ? 0x9A9A9A : mc.color;
                    int h = shape.isEmpty() ? 14 : (int) Math.round(Math.max(1, Math.min(16, shape.getMax(net.minecraft.util.math.Direction.Axis.Y) * 16)));
                    int faces = 0;
                    for (int i = 0; i < 6; i++) {
                        n.set(x + dirs[i][0], y + dirs[i][1], z + dirs[i][2]);
                        BlockState ns = world.getBlockState(n);
                        boolean hidden = ns.isOpaqueFullCube() || (water && !ns.getFluidState().isEmpty()) || (lava && !ns.getFluidState().isEmpty());
                        if (i == 1 && h < 16) hidden = false;   // the top of a slab is always visible
                        if (!hidden) faces |= 1 << i;
                    }
                    if (faces == 0) continue;
                    snap.add(x, y, z, rgb, faces | (h << 6) | (water ? WorldSnapshot.WATER : 0) | (lava ? WorldSnapshot.LAVA : 0));
                }
            }
        }
    }

}
