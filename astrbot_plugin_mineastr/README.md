# MineAstr AstrBot 插件

MineAstr 为 AstrBot 提供一个 `minecraft` 平台适配器。插件启动后会监听 WebSocket，等待 MineAstr NeoForge Mod 主动连接。

Minecraft 玩家聊天会被转换为 AstrBot 中的同一个群聊会话：

- 平台：`minecraft`
- 群组/会话 ID：`minecraft`
- 发送者：Minecraft 玩家 UUID 和玩家名

AstrBot 对该会话的文本回复会回传给所有已连接的 Minecraft 服务器，并在游戏内广播为：

```text
[AstrBot] 回复内容
```

## 最简单配置

如果 AstrBot 和 Minecraft 服务器都在同一台电脑上，只需要改一项：

1. 在 AstrBot WebUI 中启用 `minecraft` 平台适配器。
2. 把 `token` 从 `change-me` 改成一个你自己写的随机字符串，例如 `mineastr-2026-xxxx`。
3. 打开 Minecraft 侧生成的 `mineastr-common.toml`，把里面的 `token` 改成同一个字符串。
4. 重启 AstrBot 的 `minecraft` 平台适配器和 Minecraft 服务器。

默认连接地址是：

```text
ws://127.0.0.1:8765/ws
Authorization: Bearer <你的 token>
```

## 跨机器部署

如果 AstrBot 和 Minecraft 服务器不在同一台机器上：

1. AstrBot 侧 `host` 不要填 `127.0.0.1`，应改成 Minecraft 服务器能访问到的监听地址。常见做法是填 `0.0.0.0`。
2. Minecraft Mod 侧 `websocketUrl` 中的 `127.0.0.1` 改成 AstrBot 机器的 IP 或域名。
3. 确认 AstrBot 机器防火墙放行 `port` 对应端口，默认是 `8765`。

示例：

```text
AstrBot 侧：
host = 0.0.0.0
port = 8765
path = /ws

Minecraft Mod 侧：
websocketUrl = "ws://192.168.1.20:8765/ws"
```

## 安装

1. 将 `astrbot_plugin_mineastr` 目录复制或软链接到 AstrBot 的插件目录。
2. 如果 AstrBot 没有自动安装依赖，请在 AstrBot 环境中运行：

```bash
pip install -r astrbot_plugin_mineastr/requirements.txt
```

3. 在 AstrBot WebUI 中启用 `minecraft` 平台适配器。
4. 将 AstrBot 侧的 `token`、监听地址、端口和路径与 Minecraft Mod 的 common 配置保持一致。

## 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `host` | `127.0.0.1` | WebSocket 监听地址。单机部署通常保持默认；跨机器连接时改为 Minecraft 服务器可访问的地址。 |
| `port` | `8765` | WebSocket 监听端口。被占用时可以换成其他未使用端口，并同步修改 Mod 的 `websocketUrl`。 |
| `path` | `/ws` | WebSocket 路径。一般保持默认；修改后也要同步修改 Mod 的 `websocketUrl`。 |
| `token` | `change-me` | Mod 连接时使用的 Bearer Token。两端必须完全一致，建议改成随机字符串。 |
| `group_id` | `minecraft` | AstrBot 中用于承载所有 Minecraft 聊天的虚拟群组 ID。一般不需要修改。 |
| `group_name` | `Minecraft` | AstrBot 中显示的群组名称，只影响识别和展示。 |
| `bot_id` | `astrbot` | 虚拟 Minecraft 平台中的机器人账号 ID。一般不需要修改。 |
| `bot_display_name` | `AstrBot` | 回复广播到游戏内时显示在方括号里的名称。 |
| `max_message_length` | `1000` | 转发到 AstrBot 的单条玩家消息最大长度，超出部分会被截断。 |

## 故障排查

- Mod 日志提示 `401` 或连接后立即断开：检查两端 `token` 是否完全一致。
- Mod 一直显示 `未连接`：确认 AstrBot 插件已加载，`minecraft` 平台适配器已启用，端口没有被防火墙或其他程序占用。
- AstrBot 收到消息但没有回复：这是 AstrBot 群聊规则、唤醒词或权限设置决定的，需要检查 AstrBot 的回复策略。

插件级 `_conf_schema.json` 仅用于展示和发现配置。实际生效的 WebSocket 参数以 AstrBot WebUI 中 `minecraft` 平台适配器的配置为准。
