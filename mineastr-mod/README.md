# MineAstr NeoForge Mod

MineAstr 是一个服务端侧 NeoForge 1.21.1 Mod，用于把 Minecraft 聊天桥接到 AstrBot，并把 AstrBot 的文本回复广播回游戏。

## 构建

请安装 JDK 21，然后运行：

```powershell
.\gradlew.bat build
```

构建产物位于 `build/libs/`。

## 安装与运行

独立服务器使用时，只需要把构建出的 jar 放到 NeoForge 服务端的 `mods` 目录，客户端无需安装。

单人本地世界也可以使用：把 jar 放到 NeoForge 1.21.1 客户端的 `mods` 目录，进入单人世界后由集成服务器加载 MineAstr。

首次启动后，编辑生成的 common 配置，并确保这些值与 AstrBot 插件配置一致：

```toml
enabled = true
websocketUrl = "ws://127.0.0.1:8765/ws"
token = "change-me"
serverId = "minecraft"
serverName = "Minecraft 服务器"
botDisplayName = "AstrBot"
reconnectSeconds = 5
maxMessageLength = 1000
```

如果 AstrBot 与 Minecraft 服务器不在同一台机器上，请把 `websocketUrl` 中的 `127.0.0.1` 改成 AstrBot 所在机器能被服务器访问到的地址，并检查防火墙放行端口。

## 命令

- `/mineastr status`：查看连接状态。
- `/mineastr reconnect`：主动断开当前连接并立即重连。

两个命令都需要权限等级 2。

## 故障排查

- 状态一直是 `未连接`：确认 AstrBot 已启动，`minecraft` 平台适配器已启用，`websocketUrl` 指向正确地址。
- 日志中出现 `401` 或认证失败：检查两端 `token` 是否一致。
- 玩家聊天没有进入 AstrBot：确认 `enabled = true`，并检查服务器日志中是否有连接失败或 JSON 错误。
- 游戏里没有看到回复：AstrBot 是否回复由 AstrBot 自身的群聊规则、唤醒词和权限决定。
