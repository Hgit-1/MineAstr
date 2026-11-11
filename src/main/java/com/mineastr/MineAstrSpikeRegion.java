package com.mineastr;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

record MineAstrSpikeRegion(String dimension, BlockPos min, BlockPos max) {
    static MineAstrSpikeRegion parse(String configured) {
        String[] fields = configured.split(",", -1);
        if (fields.length != 7 || ResourceLocation.tryParse(fields[0].strip()) == null) {
            throw new IllegalArgumentException("沙盒/禁区格式应为 dimension,minX,minY,minZ,maxX,maxY,maxZ");
        }
        int[] coordinates = new int[6];
        for (int i = 0; i < coordinates.length; i++) {
            coordinates[i] = Integer.parseInt(fields[i + 1].strip());
            if (Math.abs((long) coordinates[i]) > 30_000_000L) {
                throw new IllegalArgumentException("沙盒/禁区坐标超出世界边界");
            }
        }
        return new MineAstrSpikeRegion(fields[0].strip(),
                new BlockPos(Math.min(coordinates[0], coordinates[3]), Math.min(coordinates[1], coordinates[4]), Math.min(coordinates[2], coordinates[5])),
                new BlockPos(Math.max(coordinates[0], coordinates[3]), Math.max(coordinates[1], coordinates[4]), Math.max(coordinates[2], coordinates[5])));
    }

    private AABB bounds() {
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
    }

    boolean contains(String world, AABB body) {
        AABB bounds = bounds();
        return dimension.equals(world) && body.minX >= bounds.minX && body.minY >= bounds.minY && body.minZ >= bounds.minZ
                && body.maxX <= bounds.maxX && body.maxY <= bounds.maxY && body.maxZ <= bounds.maxZ;
    }

    boolean intersects(String world, AABB body) {
        return dimension.equals(world) && bounds().intersects(body);
    }
}
