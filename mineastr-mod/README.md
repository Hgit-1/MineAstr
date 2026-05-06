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

## 配置文件位置

首次启动游戏或服务器后，NeoForge 会生成 common 配置文件：

- 独立服务端：`服务端目录/config/mineastr-common.toml`
- 单人本地世界：`.minecraft/config/mineastr-common.toml`

如果没有看到这个文件，请先确认服务端或客户端至少启动过一次，并且 MineAstr jar 已经放在正确的 `mods` 目录。

## 最简单配置

如果 AstrBot 和 Minecraft 在同一台电脑上，只需要改 `token`：

1. 在 AstrBot WebUI 的 `minecraft` 平台适配器里，把 `token` 改成一个随机字符串。
2. 打开 `mineastr-common.toml`。
3. 找到 `token = "change-me"`，把引号里的内容改成同一个随机字符串。
4. 保存文件，然后重启 Minecraft 服务器或客户端。

示例：

```toml
token = "mineastr-2026-xxxx"
```

## 完整配置示例

下面是 `mineastr-common.toml` 的完整示例。TOML 配置中，`#` 开头的是说明文字；真正生效的是 `=` 后面的值。字符串必须保留英文双引号。

仓库中也提供了同样内容的示例文件：[examples/mineastr-common.toml](examples/mineastr-common.toml)。

```toml
# 是否启用 MineAstr 并连接到 AstrBot。
# 不想转发聊天时改成 false。
enabled = true

# AstrBot minecraft 平台适配器的 WebSocket 地址。
# 本机运行 AstrBot 时通常保持 ws://127.0.0.1:8765/ws。
# AstrBot 在另一台机器时，把 127.0.0.1 改成那台机器的 IP 或域名。
websocketUrl = "ws://127.0.0.1:8765/ws"

# AstrBot 插件校验的 Bearer Token。
# 这里的值必须与 AstrBot minecraft 平台适配器中的 token 完全一致。
# 建议把 change-me 改成较长随机字符串，并同时填到 AstrBot 侧。
token = "change-me"

# 发送给 AstrBot 的稳定服务器 ID。
# 只有接入多个 Minecraft 服务器时才需要改；单服通常保持 minecraft。
serverId = "minecraft"

# 发送给 AstrBot 的服务器显示名称。
# 用于日志和识别，可以写成你的服务器名称。
serverName = "Minecraft 服务器"

# AstrBot 消息广播到 Minecraft 时显示的名称。
# 游戏内会显示为 [名称] 回复内容。
botDisplayName = "AstrBot"

# WebSocket 断开后的重连间隔，单位为秒。
# 网络不稳定时可以适当调大。
reconnectSeconds = 5

# 转发到 AstrBot 的单条玩家聊天最大长度。
# 超过这个长度的消息会被截断。
maxMessageLength = 1000
```

## 跨机器部署

如果 AstrBot 和 Minecraft 服务器不在同一台机器上：

1. AstrBot 侧 `host` 建议填 `0.0.0.0`，`port` 和 `path` 可以保持默认。
2. Minecraft Mod 侧把 `websocketUrl` 改成 AstrBot 机器的 IP 或域名。
3. 确认 AstrBot 机器的防火墙放行对应端口，默认是 `8765`。

示例：

```toml
websocketUrl = "ws://192.168.1.20:8765/ws"
```

## 配置格式注意事项

- `enabled` 只能写 `true` 或 `false`，不要加引号。
- `reconnectSeconds` 和 `maxMessageLength` 是数字，不要加引号。
- `websocketUrl`、`token`、`serverId`、`serverName`、`botDisplayName` 是字符串，必须保留英文双引号。
- 不要删除 `=`，不要把中文说明文字写到 `=` 后面。
- `websocketUrl` 的格式是 `ws://地址:端口/路径`，例如 `ws://127.0.0.1:8765/ws`。

## 命令

- `/mineastr status`：查看连接状态。
- `/mineastr reconnect`：主动断开当前连接并立即重连。

两个命令都需要权限等级 2。

## 故障排查

- 状态一直是 `未连接`：确认 AstrBot 已启动，`minecraft` 平台适配器已启用，`websocketUrl` 指向正确地址。
- 日志中出现 `401` 或认证失败：检查两端 `token` 是否完全一致。
- 玩家聊天没有进入 AstrBot：确认 `enabled = true`，并检查服务器日志中是否有连接失败或 JSON 错误。
- 游戏里没有看到回复：AstrBot 是否回复由 AstrBot 自身的群聊规则、唤醒词和权限决定。
