package com.mineastr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

final class MineAstrSpikePath {
    static final int MAX_DISTANCE = 32;
    static final int MAX_VISITED = 512;

    private MineAstrSpikePath() {}

    static List<BlockPos> find(BlockPos start, BlockPos target, Predicate<BlockPos> walkable) {
        if (start.getY() != target.getY() || start.distManhattan(target) > MAX_DISTANCE) {
            throw new IllegalArgumentException("Phase 0 只支持同一高度、32 格内的地面移动");
        }
        if (!walkable.test(start) || !walkable.test(target)) {
            throw new IllegalArgumentException("起点或目标不是已加载的安全地面");
        }
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        open.add(start);
        previous.put(start, start);
        while (!open.isEmpty()) {
            BlockPos current = open.removeFirst();
            if (current.equals(target)) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos step = target; !step.equals(start); step = previous.get(step)) path.add(step);
                Collections.reverse(path);
                return List.copyOf(path);
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (previous.containsKey(next) || start.distManhattan(next) > MAX_DISTANCE || !walkable.test(next)) continue;
                if (previous.size() >= MAX_VISITED) throw new IllegalStateException("Phase 0 寻路达到 512 节点预算");
                previous.put(next, current);
                open.addLast(next);
            }
        }
        throw new IllegalStateException("沙盒内没有已加载的平地路径");
    }
}
