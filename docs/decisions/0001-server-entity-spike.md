# ADR 0001：服务端 Agent 的 Phase 0 实验

状态：**暂选 A，实验性；不批准生产后端切换**。关联 [Issue #4](https://github.com/Hgit-1/MineAstr/issues/4)。

## 范围与决策

本 PR 只验证服务端直接执行的最小链路，不替换 AstrBot 工具，不修改 WebSocket 查询语义，也不删除 MineFlayer。默认仍为原有行为。

暂选 **A：`ServerPlayer` 路线**，使用 NeoForge 自带 `FakePlayer` 作为 Phase 0 实验夹具，而不是复制第三方 Bot 实现。它已有原版背包、物品组件和 `ServerPlayerGameMode`，可实测挖掘、放置及保护事件，无需协议翻译、Node、HTTP 或 HMAC 通道。

- **A / ServerPlayer**：能直接使用玩家动作和注册表。代价是连接/玩家列表、皮肤、登录身份、死亡、tick 与重启生命周期必须另行设计。当前 `FakePlayer` 不属于已认证的在线玩家列表，且自带免伤、禁用 GUI、空 tick 等限制，不能直接宣称生存能力对齐。
- **B / LivingEntity 或 PathfinderMob**：适合原版导航与生物生命周期，但需要新的实体类型/客户端渲染，并非所有 Mod/领地事件都把它视为玩家；还需独立背包语义。
- **C / 混合**：可以把导航和展示交给生物，再用玩家执行动作，但将引入两套位置、身份和物品状态；Phase 0 不选择这条同步成本较高的路线。

选型只对实验有效。在真实客户端展示、专服/集成服重启和常见保护 Mod 的矩阵完成前，不进入 Phase 1 默认后端切换。

## 已实现的垂直切片

- 显式开关 `enableServerEntitySpike=false`，独立的 `serverEntitySpikeRegion` 沙盒；空值或非法配置拒绝动作。
- `/mineastr entity-spike` 下的管理员命令：生成、观察、同高度移动、挖一个白名单方块、放一个白名单方块、背包存取/选槽、停止及移除。
- 所有动作在服务器线程执行；每个控制器最多一个动作，执行预算 400 tick，可取消。
- 导航为同高度、目标 32 格内、最多 512 节点的四邻接 BFS。每 tick 最多移动 0.2 格，并检查扫掠碰撞体积和实际位移；不是传送，也不是 MineFlayer 寻路功能对齐。遇到区块未加载、支撑丢失、禁区、障碍变化或预算耗尽即停止。
- 实验白名单仅含石头、泥土、圆石、橡木木板。挖掘通过原版开始/停止/取消挖掘接口及进度，放置通过 `ServerPlayerGameMode.useItemOn`，不直接 `setBlock` 绕过事件。
- 背包存取在六格内直接转移操作者与实验实体的真实物品；不生成物品，不读取其他玩家背包。观察复用 `MineAstrTools`。
- 实体以固定的、独立于真人账号的实验 UUID 运行。发现 UUID/在线名称冲突时拒绝生成，绝不挤掉真人。
- 独立世界私有 `data/mineastr_entity_spike.dat` 保存原生玩家 NBT（含物品组件）和恢复意图。退出/停止前保存；恢复时重新检查维度、地面、沙盒、禁区和区块状态。不强制加载未加载区块；未完成的挖掘/移动不自动重放。
- 身份资料在实体加入跟踪前发送给客户端，玩家随后登录时补发。实际皮肤、Tab、换维度和客户端可见性仍需实机验收。

## 安全与兼容约束

沙盒检查覆盖完整玩家体积，而不只是脚部坐标。`agentForbiddenRegions` 同样生效，非法禁区配置会使实验启动失败。动作检查已加载区块、世界边界、原版交互距离、出生点保护与视线；未知机器、容器 GUI、多方块放置和复杂 Mod 动作不在本次支持范围。

管理员在实验命令中显式请求世界编辑；普通玩家无权调用。实验实体的背包和精确坐标仅存在本地存档/管理员观察中，不接入公共 RAG 或公开事件历史。测试前应备份世界。

不改变 `agent_status / agent_observe / agent_task / agent_cancel / agent_events / agent_waypoints`：这些仍指向原 MineFlayer 后端。实验命令不是远程执行后端，不应让 AstrBot 把它当作已迁移能力。

## 安装实验 ZIP

要求 Minecraft 1.21.1、NeoForge 21.1.219、JDK 21。**只在独立测试实例中使用**。

1. 将 ZIP 中 `mods/` 下的 `*-entity-spike.jar` 放入该实例的 `mods/`；不要同时放普通 MineAstr JAR。
2. 首次启动后关闭实例，参考 `examples/mineastr-entity-spike.toml` 修改原有 `config/mineastr-common.toml`，不要用示例覆盖现有凭据。
3. 设置 `enableAgent=false`、`enableServerEntitySpike=true`，把 `serverEntitySpikeRegion` 改为自己测试场地的维度及包围盒。实验路径无需 Node 或 `runtime.zip`。
4. 重新启动，在沙盒内准备平整、已加载的石头地面。使用 `/mineastr entity-spike spawn X Y Z`，其中 Y 为脚部高度，地面位于 Y-1。
5. 使用 `observe`；手持白名单材料执行 `deposit 3`、`select 0`；对原版交互距离内的位置测试 `dig X Y Z` / `place X Y Z`，通过 `status` 核对终态。
6. `goto X Y Z` 只支持同高度近距离地面。`stop` 取消动作；`despawn` 保存背包并取消自动恢复意图，之后再次 `spawn` 会保留该背包。
7. 如需恢复 MineFlayer，关闭实验开关，换回普通构建并恢复原 `enableAgent` 配置。实验 ZIP 有意不包含旧 Node runtime，因此不是普通发行包的完整替代。

## 构建与验证

```powershell
.\gradlew.bat -PserverEntitySpikeOnly=true test runGameTestServer packageServerEntitySpike
```

`packageServerEntitySpike` 依赖单元测试及 GameTest 服务端，通过后生成 `build/distributions/mineastr-0.12.0-dev.3-entity-spike.zip`。实验构建不运行 npm、不打包 Node runtime；普通 `build` 的 MineFlayer 打包路径保持不变。GameTest 源码不进入安装 JAR。

测试覆盖：区域解析/全体积边界、禁区相交、有限导航/距离/高度预算；GameTest 中的真实实体生成、物品转移、观察、挖掘/放置、取消保护事件、实际移动、停止、NBT 序列化重载和原生背包恢复。

**NBT 重载测试不是完整 Minecraft 进程重启测试。** Headless GameTest 服务端也不是单人集成服务器的替代证明。当前 PR 应保持实验/草案状态，以下项目完成前不得标记 Issue #4 整体验收通过：

- 单人内置服真实客户端：可见性、移动展示、皮肤、退出存档后重启恢复。
- 独立专服：两个独立进程启动间的恢复，无真人在线时的区块行为。
- Create 等 Mod 物品组件的存取、领地/保护 Mod 的拒绝路径、死亡/换维度和掉线。
- 同一机器、同一世界和同一工作量的 MineFlayer 与服务端实体基线对照。

## 性能证据与后续门槛

`status` 给出控制器 mean/max tick 微秒、采样 tick 数、JVM 已用堆、Node 子进程数，以及本实现不使用控制 HTTP/HMAC 的标记。GameTest 输出 `MINEASTR_SPIKE_METRICS`。这些是观测值，不应把整个 JVM 堆误称为该实体的独占内存，控制器 tick 也不等于整服 MSPT。

本地首轮证据（2026-11-11，Windows/JDK 21/NeoForge 21.1.219）：39 项 JUnit 通过，1 条真实世界 GameTest 生命周期流程通过。该流程采样 138 个控制器 tick，平均约 374.564 微秒、最大约 16206.299 微秒，Node 子进程为 0。采样期间整个 JVM 已用堆约 377.8 MB；这不是原型的独占内存，也没有同负载 MineFlayer 对照，不能据此宣称性能提升。后续重复运行的精确值以对应 Actions 日志为准。

Phase 0 不删除任何生产转译代码，实际删除量 **0 行**。候选替换范围包括 `MineAstrAgentManager` 中的进程/HTTP 管理、`MineAstrAgentInventoryAuthority` 的 HMAC 路径与 `src/agent` 执行器；它们暂时全部保留作为迁移回退，只有功能、保护事件和真实性能矩阵达标后才逐项退役。

本次新增的有限 Java 导航不是最终导航选型。Phase 1 前仍需评估增量 A*、路径预算、Mod 碰撞、门/楼梯、区块加载策略及玩家形态实体的可靠生命周期。
