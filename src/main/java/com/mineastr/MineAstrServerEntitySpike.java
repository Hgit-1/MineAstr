package com.mineastr;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Operator-only Phase 0 fixture, not a replacement for the AstrBot task backend. */
final class MineAstrServerEntitySpike implements AutoCloseable {
    static final GameProfile PROFILE = new GameProfile(
            UUID.nameUUIDFromBytes("MineAstr:server-entity-spike".getBytes(StandardCharsets.UTF_8)), "MineAstrSpike");
    private static final int MAX_WORK_TICKS = 400;
    private final Supplier<Settings> settings;
    private final ArrayDeque<BlockPos> path = new ArrayDeque<>();
    private MinecraftServer server;
    private MineAstrEntitySpikeData data;
    private MineAstrSpikeRegion sandbox;
    private List<MineAstrSpikeRegion> forbidden = List.of();
    private FakePlayer player;
    private BlockPos mining;
    private BlockState miningState;
    private float miningProgress;
    private int workTicks;
    private int stalledTicks;
    private int ticks;
    private long measuredTicks;
    private long totalTickNanos;
    private long maxTickNanos;
    private String state = "disabled";
    private String lastError = "";

    record Settings(boolean enabled, String sandbox, List<? extends String> forbidden) {}

    MineAstrServerEntitySpike() {
        this(() -> new Settings(MineAstrConfig.ENABLE_SERVER_ENTITY_SPIKE.getAsBoolean(),
                MineAstrConfig.SERVER_ENTITY_SPIKE_REGION.get(), MineAstrConfig.AGENT_FORBIDDEN_REGIONS.get()));
    }

    MineAstrServerEntitySpike(Supplier<Settings> settings) {
        this.settings = settings;
    }

    void start(MinecraftServer currentServer) {
        if (server != null) close();
        server = currentServer;
        state = "disabled";
        lastError = "";
        ticks = 0;
        measuredTicks = 0;
        totalTickNanos = 0;
        maxTickNanos = 0;
        assertServerThread();
        if (!settings.get().enabled()) return;
        try {
            sandbox = MineAstrSpikeRegion.parse(settings.get().sandbox());
            forbidden = settings.get().forbidden().stream().map(MineAstrSpikeRegion::parse).toList();
            data = MineAstrEntitySpikeData.get(server);
            state = data.active ? "restore_waiting" : "ready";
            restore();
        } catch (IllegalArgumentException | IllegalStateException exc) {
            fail(exc.getMessage());
        }
    }

    void spawn(ServerLevel level, BlockPos position) {
        requireEnabled();
        if (player != null) throw new IllegalStateException("实验实体已经存在");
        if (data.active) throw new IllegalStateException("已有待恢复的实体；请等待其区块加载或先执行 despawn");
        create(level, position, false);
    }

    private void create(ServerLevel level, BlockPos position, boolean restoring) {
        if (!walkable(level, position)) throw new IllegalArgumentException("生成位置不在已加载的安全沙盒地面内");
        if (server.getPlayerList().getPlayerByName(PROFILE.getName()) != null) {
            throw new IllegalStateException("实验名称已被在线玩家使用");
        }
        for (ServerLevel world : server.getAllLevels()) {
            Entity existing = world.getEntity(PROFILE.getId());
            if (existing != null && !existing.isRemoved()) throw new IllegalStateException("实验 UUID 已被使用");
        }
        FakePlayer created = new FakePlayer(level, PROFILE);
        if (!data.player.isEmpty()) created.load(data.player.copy());
        created.setUUID(PROFILE.getId());
        created.moveTo(Vec3.atBottomCenterOf(position), 0, 0);
        created.setDeltaMovement(Vec3.ZERO);
        created.setOnGround(true);
        created.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(created)));
        level.addNewPlayer(created);
        if (level.getEntity(PROFILE.getId()) != created) {
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(PROFILE.getId())));
            throw new IllegalStateException("实体加入事件被拒绝");
        }
        player = created;
        state = restoring ? "restored" : "idle";
        lastError = "";
        data.active = true;
        save();
    }

    private void restore() {
        if (data == null || !data.active || player != null) return;
        ResourceLocation dimension = ResourceLocation.tryParse(data.dimension);
        if (dimension == null) throw new IllegalStateException("保存的实验维度无效");
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        var position = data.player.getList("Pos", Tag.TAG_DOUBLE);
        if (level == null || position.size() != 3) throw new IllegalStateException("保存的实验位置无效");
        double x = position.getDouble(0), y = position.getDouble(1), z = position.getDouble(2);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
            throw new IllegalStateException("保存的实验坐标无效");
        }
        BlockPos feet = BlockPos.containing(x, y, z);
        if (!level.hasChunkAt(feet)) return;
        create(level, feet, true);
    }

    void tick() {
        if (server == null || data == null) return;
        assertServerThread();
        if (!settings.get().enabled()) {
            close();
            return;
        }
        long start = System.nanoTime();
        try {
            ticks++;
            if (player == null) {
                if (ticks % 20 == 0 && "restore_waiting".equals(state)) restore();
                return;
            }
            if (!walkable(player.serverLevel(), player.blockPosition())) {
                cancel();
                fail("实验实体当前位置不再安全；请检查沙盒后 despawn");
                return;
            }
            player.gameMode.tick();
            if (mining != null) tickMining();
            else if (!path.isEmpty()) tickWalking();
            if (ticks % 100 == 0) save();
        } catch (IllegalArgumentException | IllegalStateException exc) {
            cancel();
            fail(exc.getMessage());
        } finally {
            long elapsed = System.nanoTime() - start;
            measuredTicks++;
            totalTickNanos += elapsed;
            maxTickNanos = Math.max(maxTickNanos, elapsed);
        }
    }

    void goTo(BlockPos target) {
        requireIdle();
        path.addAll(MineAstrSpikePath.find(player.blockPosition(), target, pos -> walkable(player.serverLevel(), pos)));
        workTicks = 0;
        stalledTicks = 0;
        state = path.isEmpty() ? "completed" : "walking";
    }

    private void tickWalking() {
        if (++workTicks > MAX_WORK_TICKS) throw new IllegalStateException("移动超过 400 tick 预算");
        BlockPos next = path.peekFirst();
        if (!walkable(player.serverLevel(), next)) throw new IllegalStateException("路径变化，停止移动");
        Vec3 difference = Vec3.atBottomCenterOf(next).subtract(player.position());
        if (difference.lengthSqr() < 0.01) {
            path.removeFirst();
            if (path.isEmpty()) complete();
            return;
        }
        Vec3 step = difference.normalize().scale(Math.min(0.2, difference.length()));
        AABB swept = player.getBoundingBox().expandTowards(step);
        if (!allowed(player.serverLevel(), swept) || !player.serverLevel().noCollision(player, swept)) {
            throw new IllegalStateException("实际移动体积受阻，停止移动");
        }
        Vec3 before = player.position();
        player.move(MoverType.SELF, step);
        player.setOnGround(true);
        if (player.position().distanceToSqr(before) < 0.0001) stalledTicks++;
        else stalledTicks = 0;
        if (stalledTicks > 20) throw new IllegalStateException("实体无实际位移，停止移动");
    }

    void dig(BlockPos target) {
        requireIdle();
        requireReach(target);
        BlockState block = player.serverLevel().getBlockState(target);
        if (!simpleBlock(block.getBlock()) || block.hasBlockEntity()) {
            throw new IllegalArgumentException("Phase 0 只允许挖掘沙盒中的石头、泥土、圆石和橡木木板");
        }
        requireVisible(target, Vec3.atCenterOf(target));
        mining = target.immutable();
        miningState = block;
        miningProgress = 0;
        workTicks = 0;
        state = "mining";
        player.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                Direction.UP, player.serverLevel().getMaxBuildHeight(), 0);
    }

    private void tickMining() {
        if (++workTicks > MAX_WORK_TICKS) throw new IllegalStateException("挖掘超时或被保护事件拒绝");
        requireReach(mining);
        BlockState current = player.serverLevel().getBlockState(mining);
        if (current.isAir()) {
            mining = null;
            complete();
            return;
        }
        if (current != miningState) throw new IllegalStateException("挖掘目标已改变");
        requireVisible(mining, Vec3.atCenterOf(mining));
        miningProgress += current.getDestroyProgress(player, player.serverLevel(), mining);
        if (miningProgress >= 1) {
            player.gameMode.handleBlockBreakAction(mining, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.UP, player.serverLevel().getMaxBuildHeight(), 0);
        }
    }

    void place(BlockPos target) {
        requireIdle();
        requireReach(target);
        BlockPos support = target.below();
        requireReach(support);
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem item) || !simpleBlock(item.getBlock())) {
            throw new IllegalArgumentException("先向实验实体存入并选中石头、泥土、圆石或橡木木板");
        }
        if (!player.serverLevel().getBlockState(target).isAir() || new AABB(target).intersects(player.getBoundingBox())
                || !simpleBlock(player.serverLevel().getBlockState(support).getBlock())) {
            throw new IllegalArgumentException("放置需要空目标和白名单支撑面，且不得与实体重叠");
        }
        Vec3 hitPosition = Vec3.atBottomCenterOf(target);
        requireVisible(support, hitPosition.add(0, -0.001, 0));
        var result = player.gameMode.useItemOn(player, player.serverLevel(), held, InteractionHand.MAIN_HAND,
                new BlockHitResult(hitPosition, Direction.UP, support, false));
        if (!result.consumesAction() || !player.serverLevel().getBlockState(target).is(item.getBlock())) {
            throw new IllegalStateException("原版放置逻辑/保护事件拒绝了操作");
        }
        complete();
    }

    int transfer(ServerPlayer operator, int slot, int count, boolean withdraw) {
        requireIdle();
        if (operator.serverLevel() != player.serverLevel() || operator.distanceToSqr(player) > 36
                || !allowed(operator.serverLevel(), operator.getBoundingBox()) || slot < 0 || slot >= 36 || count < 1 || count > 64) {
            throw new IllegalArgumentException("背包操作要求操作者在沙盒内六格范围，槽位 0-35，数量 1-64");
        }
        ItemStack source = withdraw ? player.getInventory().getItem(slot) : operator.getMainHandItem();
        if (source.isEmpty()) throw new IllegalArgumentException("来源槽位为空");
        ItemStack remaining = source.copyWithCount(Math.min(count, source.getCount()));
        int requested = remaining.getCount();
        (withdraw ? operator.getInventory() : player.getInventory()).add(remaining);
        int moved = requested - remaining.getCount();
        source.shrink(moved);
        player.getInventory().setChanged();
        operator.getInventory().setChanged();
        operator.containerMenu.broadcastChanges();
        save();
        return moved;
    }

    void select(int slot) {
        requireIdle();
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("快捷栏槽位必须为 0-8");
        player.getInventory().selected = slot;
        save();
    }

    void cancel() {
        assertServerThread();
        path.clear();
        if (mining != null && player != null && player.serverLevel().hasChunkAt(mining)) {
            player.gameMode.handleBlockBreakAction(mining, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    Direction.UP, player.serverLevel().getMaxBuildHeight(), 0);
        }
        mining = null;
        if (player != null) {
            player.setDeltaMovement(Vec3.ZERO);
            state = "canceled";
        }
    }

    void despawn() {
        requireEnabled();
        cancel();
        save();
        data.active = false;
        data.setDirty();
        remove();
        state = "ready";
    }

    void announceTo(ServerPlayer observer) {
        if (player != null) observer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
    }

    JsonObject status() {
        JsonObject status = new JsonObject();
        status.addProperty("experimental", true);
        status.addProperty("route", "server_player_fakeplayer_spike");
        status.addProperty("state", state);
        status.addProperty("last_error", lastError);
        status.addProperty("node_required", false);
        status.addProperty("control_http_port", 0);
        status.addProperty("hmac_channel_used", false);
        status.addProperty("measured_ticks", measuredTicks);
        status.addProperty("mean_controller_tick_us", measuredTicks == 0 ? 0 : totalTickNanos / measuredTicks / 1000.0);
        status.addProperty("max_controller_tick_us", maxTickNanos / 1000.0);
        Runtime runtime = Runtime.getRuntime();
        status.addProperty("jvm_used_heap_bytes", runtime.totalMemory() - runtime.freeMemory());
        status.addProperty("node_child_processes", ProcessHandle.current().children().filter(process -> process.info().command()
                .map(command -> Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT).startsWith("node")).orElse(false)).count());
        if (player != null) {
            status.add("player", MineAstrTools.buildPlayerState(player));
            status.add("inventory", MineAstrTools.buildInventory(player, false));
        }
        return status;
    }

    private boolean allowed(ServerLevel level, AABB body) {
        String dimension = level.dimension().location().toString();
        if (sandbox == null || !sandbox.contains(dimension, body)
                || forbidden.stream().anyMatch(region -> region.intersects(dimension, body))
                || body.minY < level.getMinBuildHeight() || body.maxY > level.getMaxBuildHeight()) return false;
        for (double x : new double[] {body.minX, Math.nextDown(body.maxX)}) {
            for (double z : new double[] {body.minZ, Math.nextDown(body.maxZ)}) {
                BlockPos corner = BlockPos.containing(x, body.minY, z);
                if (!level.hasChunkAt(corner) || !level.getWorldBorder().isWithinBounds(corner)) return false;
            }
        }
        return true;
    }

    private boolean walkable(ServerLevel level, BlockPos feet) {
        AABB body = new AABB(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2, feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8);
        if (!allowed(level, body) || !allowed(level, new AABB(feet.below()))) return false;
        BlockState floor = level.getBlockState(feet.below());
        return !floor.hasBlockEntity() && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CACTUS)
                && floor.isFaceSturdy(level, feet.below(), Direction.UP) && level.noCollision(player, body)
                && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.below()).isEmpty();
    }

    private void requireReach(BlockPos target) {
        if (!allowed(player.serverLevel(), player.getBoundingBox()) || !allowed(player.serverLevel(), new AABB(target))
                || !player.canInteractWithBlock(target, 0) || !player.serverLevel().mayInteract(player, target)) {
            throw new IllegalArgumentException("目标未加载、越界、位于禁区或不在玩家交互距离内");
        }
    }

    private void requireVisible(BlockPos target, Vec3 end) {
        BlockHitResult hit = player.serverLevel().clip(new ClipContext(player.getEyePosition(), end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != BlockHitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) {
            throw new IllegalArgumentException("目标被遮挡，拒绝隔墙交互");
        }
    }

    private static boolean simpleBlock(Block block) {
        return block == Blocks.STONE || block == Blocks.DIRT || block == Blocks.COBBLESTONE || block == Blocks.OAK_PLANKS;
    }

    private void requireEnabled() {
        assertServerThread();
        if (!settings.get().enabled() || data == null || sandbox == null) {
            throw new IllegalStateException("实验未启用或沙盒配置无效；请设置 enableServerEntitySpike 和 serverEntitySpikeRegion 后重启");
        }
    }

    private void requireIdle() {
        requireEnabled();
        if (player == null) throw new IllegalStateException("请先在沙盒内 spawn 实验实体");
        if (!path.isEmpty() || mining != null) throw new IllegalStateException("已有实验动作；请先 stop");
        if (!walkable(player.serverLevel(), player.blockPosition())) throw new IllegalStateException("实体不在安全地面上");
    }

    private void assertServerThread() {
        if (server == null || !server.isSameThread()) throw new IllegalStateException("实验动作只能在服务器线程执行");
    }

    private void complete() {
        state = "completed";
        lastError = "";
        save();
    }

    private void fail(String error) {
        state = "failed";
        lastError = error;
    }

    private void save() {
        if (player == null || data == null) return;
        data.player = player.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        data.dimension = player.serverLevel().dimension().location().toString();
        data.setDirty();
    }

    private void remove() {
        if (player == null) return;
        player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(PROFILE.getId())));
        player = null;
    }

    @Override
    public void close() {
        if (server == null) return;
        assertServerThread();
        cancel();
        save();
        remove();
        server = null;
        data = null;
        sandbox = null;
        state = "disabled";
    }
}
