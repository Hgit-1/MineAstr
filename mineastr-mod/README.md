# MineAstr NeoForge Mod

MineAstr 是一个 NeoForge 1.21.1 双端 Mod，用于把 Minecraft 聊天桥接到 AstrBot，并把 AstrBot 的文本回复广播回游戏。

从 AstrBot 侧启用 MineAstr LLM 工具后，机器人还可以主动向 Mod 查询服务器状态、在线玩家列表，并在玩家客户端允许时请求低清晰度截图。

## 构建

请安装 JDK 21，然后运行：

```powershell
.\gradlew.bat build
```

构建产物位于 `build/libs/`。

## 安装与运行

独立服务器使用时，把构建出的 jar 放到 NeoForge 服务端的 `mods` 目录即可提供聊天桥接、状态查询和在线玩家查询。客户端没有安装 MineAstr 也可以加入服务器。

如果希望使用截图工具，目标玩家的客户端也需要安装同一个 MineAstr jar。截图是附加功能，不影响未安装客户端 Mod 的玩家进入服务器。

单人本地世界也可以使用：把 jar 放到 NeoForge 1.21.1 客户端的 `mods` 目录，进入单人世界后由集成服务器加载 MineAstr。

## 配置文件位置

首次启动游戏或服务器后，NeoForge 会生成 common 配置文件：

- 独立服务端：`服务端目录/config/mineastr-common.toml`
- 单人本地世界：`.minecraft/config/mineastr-common.toml`

客户端安装 MineAstr 后还会生成截图配置：

- 客户端：`.minecraft/config/mineastr-client.toml`

也可以在游戏主菜单的 Mod 列表中打开 MineAstr 的配置界面修改截图选项。如果没有看到配置文件，请先确认服务端或客户端至少启动过一次，并且 MineAstr jar 已经放在正确的 `mods` 目录。

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

## 客户端截图配置

截图配置只在安装了客户端 Mod 的玩家电脑上生效。默认是 `ASK`，也就是 AstrBot 请求截图时先弹出确认窗口，玩家同意后才发送。

仓库中提供了示例文件：[examples/mineastr-client.toml](examples/mineastr-client.toml)。

```toml
# AstrBot 请求截图时客户端如何处理。
# ASK：弹出确认界面，玩家同意后发送；AUTO：自动发送；DISABLED：始终拒绝发送。
screenshotMode = "ASK"

# 发送给 AstrBot 的截图最大宽度。
screenshotMaxWidth = 240

# 发送给 AstrBot 的截图最大高度。
screenshotMaxHeight = 135

# 截图 JPEG 质量，范围 0.10 到 0.95。
screenshotJpegQuality = 0.35

# 单张截图编码后的最大字节数。
screenshotMaxBytes = 131072
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
- `screenshotMode` 是字符串，只能写 `"ASK"`、`"AUTO"` 或 `"DISABLED"`。
- `screenshotJpegQuality` 是小数，不要加引号。
- 不要删除 `=`，不要把中文说明文字写到 `=` 后面。
- `websocketUrl` 的格式是 `ws://地址:端口/路径`，例如 `ws://127.0.0.1:8765/ws`。

## 命令

- `/mineastr status`：查看连接状态。
- `/mineastr reconnect`：主动断开当前连接并立即重连。

两个命令都需要权限等级 2。

## AstrBot 主动查询

Mod 支持 AstrBot 发来的 `query` 协议消息：

- `status`：返回服务器名称、Minecraft 版本、MineAstr Mod 版本、在线人数、最大人数、运行时长和在线玩家名。
- `players`：返回在线玩家数量、最大人数、在线玩家列表，以及每名玩家是否支持截图。
- `screenshot`：向指定玩家客户端请求低清晰度截图。玩家未安装客户端 Mod、拒绝截图、禁用截图或超时时会返回失败原因。

这些查询由 AstrBot 插件中的 LLM 工具触发。实际使用时，玩家可以在 Minecraft 中直接问“现在有谁在线”“服务器状态怎么样”或“能看看我现在画面吗”，AstrBot 会在模型支持工具调用时主动查询 Mod，然后再组织回复。

## 故障排查

- 状态一直是 `未连接`：确认 AstrBot 已启动，`minecraft` 平台适配器已启用，`websocketUrl` 指向正确地址。
- 日志中出现 `401` 或认证失败：检查两端 `token` 是否完全一致。
- 玩家聊天没有进入 AstrBot：确认 `enabled = true`，并检查服务器日志中是否有连接失败或 JSON 错误。
- 游戏里没有看到回复：AstrBot 是否回复由 AstrBot 自身的群聊规则、唤醒词和权限决定。
- AstrBot 不会查询在线玩家：确认 AstrBot 当前模型支持工具调用，并且插件与 Mod 都已经更新到支持查询协议的版本。
- AstrBot 请求截图失败：确认目标玩家客户端安装了 MineAstr，并且 `screenshotMode` 不是 `"DISABLED"`。默认 `"ASK"` 模式下，玩家需要在弹窗里点击“发送截图”。
