# MineAstr AstrBot 插件

MineAstr 为 AstrBot 提供一个 `minecraft` 平台适配器。插件启动后会监听 WebSocket，等待 MineAstr NeoForge 服务端 Mod 主动连接。

默认连接信息：

```text
ws://127.0.0.1:8765/ws
Authorization: Bearer change-me
```

Minecraft 玩家聊天会被转换为 AstrBot 中的同一个群聊会话：

- 平台：`minecraft`
- 群组/会话 ID：`minecraft`
- 发送者：Minecraft 玩家 UUID 和玩家名

AstrBot 对该会话的文本回复会回传给所有已连接的 Minecraft 服务器，并在游戏内广播为：

```text
[AstrBot] 回复内容
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
| `host` | `127.0.0.1` | WebSocket 监听地址。AstrBot 与 MC 服务器在同一台机器时通常无需修改。 |
| `port` | `8765` | WebSocket 监听端口。 |
| `path` | `/ws` | WebSocket 路径。 |
| `token` | `change-me` | Mod 连接时使用的 Bearer Token，生产环境建议改成较长随机值。 |
| `group_id` | `minecraft` | AstrBot 中用于承载所有 MC 聊天的群组 ID。 |
| `group_name` | `Minecraft` | AstrBot 中显示的群组名称。 |
| `bot_id` | `astrbot` | 虚拟 Minecraft 平台中的机器人 ID。 |
| `bot_display_name` | `AstrBot` | 回复广播到游戏内时显示的名称。 |
| `max_message_length` | `1000` | 转发到 AstrBot 的单条玩家消息最大长度。 |

## 故障排查

- Mod 日志提示 `401` 或连接后立即断开：检查两端 `token` 是否一致。
- Mod 一直显示 `未连接`：确认 AstrBot 插件已加载，`minecraft` 平台适配器已启用，端口没有被防火墙或其他程序占用。
- AstrBot 收到消息但没有回复：这是 AstrBot 群聊规则、唤醒词或权限设置决定的，需要检查 AstrBot 的回复策略。

插件级 `_conf_schema.json` 仅用于展示和发现配置。实际生效的 WebSocket 参数以 AstrBot WebUI 中 `minecraft` 平台适配器的配置为准。
