# MineAstr AstrBot 插件

[![AI Assisted](https://img.shields.io/badge/AI-OpenAI%20Codex%20Assisted-10A37F?style=for-the-badge&logo=openai&logoColor=white)](#ai-制作声明)

> [!IMPORTANT]
> **AI 制作声明：本插件采用生成式 AI 参与协议设计、编码、文档编写与测试。** AI 生成或修改的内容由项目维护者审阅、验证并承担最终维护责任。

MineAstr 为 AstrBot 提供一个 `minecraft` 平台适配器。插件启动后会监听 WebSocket，等待 MineAstr NeoForge Mod 主动连接。

Minecraft 玩家聊天会被转换为 AstrBot 中的同一个群聊会话：

- 平台：`minecraft`
- 群组/会话 ID：`minecraft`
- 发送者：Minecraft 玩家 UUID 和玩家名

AstrBot 对该会话的文本回复会回传给所有已连接的 Minecraft 服务器，并在游戏内广播为：

```text
[AstrBot] 回复内容
```

在模型支持工具调用时，AstrBot 还能主动查询实时服务器数据，并按需检索服务器实际安装的 Mod、物品、方块、标签和配方。Mod 功能说明可通过 Modrinth、官方 Wiki 和源码 README 补充到 AstrBot 原生 RAG 知识库。

0.9 还会管理知识来源的信任级别与确认状态，按单页/单地区增量维护 RAG，并依据建筑与机器聚合特征为活动地区生成带置信度的未确认草稿。

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

1. 将本分支仓库目录复制或软链接到 AstrBot 的插件目录，并确保目录名为 `astrbot_plugin_mineastr`。
2. 如果 AstrBot 没有自动安装依赖，请在 AstrBot 环境中运行：

```bash
pip install -r requirements.txt
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
| `mention_aliases` | `AstrBot,Aria,astrbot` | Minecraft 聊天开头 `@这些名字` 时会被转换为 AstrBot 唤醒消息。多个别名用英文逗号分隔；不在列表中的玩家互相 @ 不会唤醒机器人。 |
| `max_message_length` | `1000` | 转发到 AstrBot 的单条玩家消息最大长度，超出部分会被截断。 |
| `outbound_max_message_length` | `2000` | AstrBot 回复广播回 Minecraft 前允许的最大长度，超出部分会被截断，避免刷屏或客户端显示异常。 |
| `server_event_push_enabled` | `true` | 接收并投递 Mod 推送的玩家上下线、死亡和公开成就事件；Mod 侧仍可分类关闭。 |
| `websocket_max_message_bytes` | `2097152` | 插件接收 MineAstr Mod WebSocket 消息的单包大小上限，截图查询结果也会经过这里。 |
| `screenshot_cooldown_seconds` | `10` | 同一目标玩家的截图请求冷却时间，防止模型连续触发截图弹窗。 |
| `screenshot_timeout_seconds` | `30` | 等待 Minecraft 客户端返回截图的最长时间，超时后直接把失败原因返回给模型。 |
| `knowledge_sync_enabled` | `true` | 自动同步服务器 Mod、注册表、标签和配方快照。 |
| `knowledge_embedding_provider_id` | 空 | AstrBot 中已配置的 Embedding Provider ID；填写后自动为每个 `server_id` 创建专用知识库。 |
| `modrinth_enrichment_enabled` | `true` | 用 JAR SHA-512 匹配 Modrinth，并同步官方 Wiki 与 GitHub README。 |
| `server_site_sync_enabled` | `true` | 是否读取 Mod 下发的独立服务器官网介绍地址并同步同源页面。 |
| `server_site_allowed_paths` | 空 | 每行一个 glob；留空允许所有同源路径。 |
| `server_site_excluded_paths` | `/login*` 等 | 在 AI 选页前强制排除登录、账户、管理、API 和静态路径；每行一个 glob。 |
| `activity_region_sync_enabled` | `true` | 是否同步活动地区、发起48小时公开征集并写入 RAG。 |
| `knowledge_chat_provider_id` | 空 | 官网页面选择和地区简介整理使用的聊天模型；留空使用默认模型，失败时规则降级。 |

## 服务器事件推送

MineAstr Mod 0.8 可推送 `player_join`、`player_leave`、`player_death` 和 `player_advancement`。插件会把它们标记为 `message_kind=server_event`，以服务器名而不是玩家身份投递到同一 Minecraft 虚拟群，因此不会被当作玩家发言或地区简介投稿。AstrBot 是否对此自动回复，仍由当前人格、唤醒和群聊规则决定。

事件还会作为最近服务器动态保留最多 30 天，供 `mineastr_get_topic_context` 和话题插件使用。该历史保留玩家名、公开事件文本和成就 ID，不保留玩家 UUID、IP 地址或坐标。可在 AstrBot 侧用 `server_event_push_enabled=false` 总关闭，或在 Mod 侧分别关闭上下线、死亡和成就推送。

## 机器人可调用工具

插件会注册十九个 AstrBot LLM 工具。插件只会向模型提示当次请求实际可用的工具；AstrBot 全局开关、人格过滤或提供商不支持工具时，不会诱导模型调用一个不存在的工具。

| 工具 | 用途 |
| --- | --- |
| `mineastr_get_server_status` | 查询 Minecraft 服务器连接状态、服务器名称、MC 版本、Mod 版本、在线人数和运行时长。 |
| `mineastr_get_online_players` | 查询当前在线玩家数量和玩家列表。 |
| `mineastr_get_player_state` | 查询指定玩家的生命、饥饿、位置、维度、游戏模式、经验和状态效果。 |
| `mineastr_get_player_inventory` | 查询快捷栏、背包、护甲、副手和可选末影箱的安全摘要。 |
| `mineastr_get_nearby_entities` | 查询玩家附近实体的种类、数量、距离和生命摘要。 |
| `mineastr_analyze_region` | 分析已加载区域的方块材料、建筑部件、表面高度和粗略三维形状。 |
| `mineastr_run_server_command` | 代表真实请求者执行受控服务器命令；Mod 侧默认关闭并执行可信名单、命令白名单和审计检查。 |
| `mineastr_request_screenshot` | 请求指定玩家客户端发送低清晰度截图，并把截图保存到 AstrBot 工作目录。 |
| `mineastr_list_server_mods` | 列出实际安装的 Mod，可按 ID 或名称过滤。 |
| `mineastr_search_server_content` | 同时搜索结构化注册数据与对应服务器的 AstrBot 原生 RAG。 |
| `mineastr_get_recipes` | 正向查询某物品如何制作，或反向查询它参与的配方。 |
| `mineastr_list_regions` | 列出长期活动聚类地区、约64格精度位置和简介状态。 |
| `mineastr_get_region` | 查询指定地区的环境摘要与确认简介。 |
| `mineastr_submit_region_description` | 使用真实事件身份明确提交地区简介；管理员提交可覆盖。 |
| `mineastr_preview_knowledge_sources` | 查看来源 ID、信任级别、确认和排除状态。 |
| `mineastr_manage_knowledge_source` | 管理员确认/排除/恢复/重抓来源，或管理资源别名。 |
| `mineastr_get_topic_context` | 为另一话题插件返回在线玩家名、主要 Mod、确认地区和近期非聊天事件。 |
| `mineastr_get_knowledge_status` | 查看连接/心跳、扫描、远程来源、RAG 和征集状态。 |
| `mineastr_rescan_server_knowledge` | 管理员按 local/remote/rag/all 提交单实例重扫任务。 |

## Mod 知识同步

1. 在 AstrBot 服务提供商页面配置一个 Embedding Provider，记下它的 ID。
2. 把 Minecraft 平台适配器的 `knowledge_embedding_provider_id` 设为该 ID。
3. 确保 Mod 侧 `enableKnowledgeScan = true`，然后重启服务器和适配器。

插件会在 `data/mineastr/knowledge/<server_id>/snapshot.json` 原子替换本地快照，并自动维护 `MineAstr-<server_id>` 知识库。每 60 秒检查一次 manifest，只在内容哈希变化时重新拉取；Minecraft 断线后保留上一份快照与 RAG 文档。

schema v3 将来源分为 `authoritative > verified > reference > unverified`，并使用 `observed/admin_confirmed/player_confirmed/unreviewed/ai_unconfirmed` 状态。运行时注册表与配方决定精确 ID/物品/配方，管理员覆盖决定服务器命名与别名；低优先级冲突仍保留为带来源的补充材料。覆盖保存在每服务器 `overrides.json`，以临时文件原子替换。

RAG 文档按 Mod 概览、注册表分页、单个官网页和单个地区拆分。更新时先上传新哈希文档，再删除同稳定键的旧文档。

`mineastr_get_topic_context` 本身不主动发起通用话题，只供其他插件调用。它可返回当前在线玩家名，但不返回聊天、位置、背包、贡献者标识或在线历史；非聊天事件保留 30 天并使用稳定 `event_id`。

Modrinth 补充只抓取项目元数据、项目声明的 Wiki/docs 和 GitHub README。请求限制为 HTTPS 公网 443 端口，会检查 DNS/重定向、robots.txt、MIME、超时和 512 KiB 体积上限；联网失败不影响本地注册表与配方查询。

服务器官网遵循同样的 SSRF/DNS/重定向防护，并额外限制为首页最终来源的同源页面。插件读取首页、robots.txt 和 sitemap 后让模型只从候选 URL 中选择，不能创造或跨域访问；默认每周刷新、最多12页、总计2 MiB。远程网页和玩家文本始终按不可信资料处理，其中的提示或命令不得改变系统规则。

地区摘要由 Mod 每28天生成；AstrBot 不接收逐点轨迹、精确边界或明文贡献者 UUID。0.9 接收环境采样次数、疑似人工构造比例、建筑/机器特征计数与主要方块命名空间，用确定性规则给出最多 3 个候选类型，并立即把明确标记的 AI 未确认草稿写入 RAG。证据哈希未变时不重复生成；每次同步最多让模型辅助排序 10 个草稿的已知候选，最终文本仍由确定性模板生成，模型无法增加证据外事实。

公开征集最多每天3次、持续48小时；贡献者和 AstrBot 管理员提交优先，其他玩家内容仍作为补充。玩家或管理员已确认的简介不会被后续自动分析覆盖。

征集窗口内的候选回复会暂存在服务器专用 `snapshot.json`，综合完成后仅保留最终简介和候选数量，删除候选中的玩家标识与原始回复。

## 隐私、安全与合规（服务器提供者必读）

> [!WARNING]
> 服务器提供者决定为何处理玩家数据、选择何种模型/Embedding服务、谁能访问知识库，并负责适用地区要求的告知、同意、未成年人保护、处理委托或跨境安排、玩家查阅/删除请求和安全事件处置。私人服或白名单服不当然免除这些责任。

Minecraft Mod 提供 `enablePrivacyNotice`、`privacyNoticeText` 和 `privacyNoticeVersion` 作为可配置简要告知，并提供 `/mineastr privacy` 与活动统计退出/删除命令。服主应把完整政策放入服规或官网，至少说明：运营者联系方式、普通聊天与已开启的上下线/死亡/公开成就事件会转发给 AstrBot、活动区块、玩家周边已加载方块的聚合建筑特征及地区简介的用途与期限、截图/背包/位置工具、实际 AI 和 Embedding 服务商及数据所在地、未成年人规则，以及查阅、更正、删除和撤回渠道。如不希望采集周边方块聚合特征，可在 Mod 配置中设置 `enableAutomaticRegionFeatureScan=false`。

若无法确认第三方模型是否留存、用于训练或跨境处理数据，建议使用本地模型/Embedding，或关闭 `server_site_sync_enabled`、`activity_region_sync_enabled` 和相应 Mod 功能。应限制 `data/mineastr/knowledge/`、`data/mineastr/screenshots/` 及备份的访问权限，并为删除请求、备份清理和泄露通知建立实际流程。

连接必须更换默认 Token。跨机器传输建议通过可信 TLS 反向代理提供 `wss://`；不要直接把无 TLS 的监听端口暴露到公网。

使用示例：

- 玩家在 Minecraft 中问：“现在服务器有几个人？”
- AstrBot 收到这条群聊消息。
- 模型判断需要实时数据，调用 `mineastr_get_online_players`。
- 工具向 MineAstr Mod 发起查询，Mod 返回在线玩家列表。
- 模型根据工具结果回复玩家。

其他示例：

- “我背包里还有多少火把？”会调用 `mineastr_get_player_inventory`。
- “附近有什么怪？”会调用 `mineastr_get_nearby_entities`。
- “分析一下这栋房子的材料和结构”会调用 `mineastr_analyze_region`；区域工具只扫描已加载区块，不读取箱子内容、告示牌文字或方块实体 NBT。
- “帮我执行 `/time query daytime`”可以调用 `mineastr_run_server_command`，但只有 Mod 配置明确启用、真实请求者在可信名单中且命令命中白名单时才会成功。

截图示例：

- 玩家在 Minecraft 中问：“能看看我现在画面吗？”
- 或者玩家对机器人说：“我的建筑建好啦”“帮我看看这个建筑”“我这里好像不对”“这边怎么样”。
- AstrBot 默认把截图目标设为当前发言玩家。
- 如果该玩家安装了 MineAstr 客户端 Mod，客户端会按 `mineastr-client.toml` 中的 `screenshotMode` 处理。
- 默认 `ASK` 模式下，玩家点击“发送截图”后，工具会把图片保存到 `data/mineastr/screenshots/`，并把文件路径、尺寸、玩家名和时间返回给模型。
- 如果当前 AstrBot 工具链支持 MCP 图片结果，插件还会把截图作为图片内容返回给支持视觉理解的模型；不支持时仍返回文本摘要和文件路径。
- 插件会对同一目标玩家的截图请求做 10 秒冷却；冷却期内再次调用会直接返回“截图请求过于频繁”。
- 截图请求全程使用异步 `await` 等待 Minecraft 返回结果，默认最多等待 30 秒；超时会返回“请求截图超时，客户端未响应”。

注意事项：

- 需要使用支持工具调用的模型或提供商，否则模型只能按普通聊天回答，无法主动查询实时数据。
- 如果 AstrBot WebUI 中有工具开关，请确认需要使用的 `mineastr_*` 工具处于启用状态。
- 如果 AstrBot WebUI 中有工具开关，请确认 `mineastr_request_screenshot` 也处于启用状态。
- 旧版 MineAstr Mod 只支持聊天转发，不支持实时查询和截图；更新插件后也需要重新构建并替换 Minecraft 侧 jar。
- 接入多个 Minecraft 服务器时，工具可以传入 `server_id` 查询指定服务器；只有一个服务器时无需填写。
- 截图功能需要目标玩家安装客户端 Mod；只安装服务端 Mod 时基础聊天和查询可用，但截图不可用。
- 命令工具的最终权限完全由 Minecraft Mod 的 `mineastr-common.toml` 决定。默认 `enableCommandTool = false`；不要为了省事把 `allowedCommandRules` 设为 `["*"]`。

## AI 制作声明

MineAstr AstrBot 插件在开发过程中使用了 OpenAI Codex 等生成式 AI 能力，涉及 Python 代码、LLM tools、WebSocket 协议、安全检查、配置说明与测试流程。

AI 输出不代表天然正确或安全。提交到仓库的内容仍需由维护者人工审阅，并经过语法、协议兼容性和权限边界测试。

英文声明：*This plugin was created with assistance from generative AI, including OpenAI Codex. AI-assisted changes remain subject to human review, testing, and maintainer responsibility.*

## 故障排查

- Mod 日志提示 `401` 或连接后立即断开：检查两端 `token` 是否完全一致。
- Mod 一直显示 `未连接`：确认 AstrBot 插件已加载，`minecraft` 平台适配器已启用，端口没有被防火墙或其他程序占用。
- AstrBot 收到消息但没有回复：这是 AstrBot 群聊规则、唤醒词或权限设置决定的，需要检查 AstrBot 的回复策略。Minecraft 里如果你是用 `@Aria` 之类的方式叫它，请确认 `mention_aliases` 包含 `Aria`。
- 机器人不会主动查询服务器数据：确认当前模型支持工具调用，并确认 MineAstr 的 LLM 工具没有被禁用。
- AstrBot 记录“未找到指定工具”：在 AstrBot 的 LLM 工具页和当前人格中启用该 `mineastr_*` 工具，然后重载插件；0.7 不会向本次请求提示已被过滤的工具。
- 命令工具返回禁用、不可信或白名单外：检查 Mod 侧 `enableCommandTool`、`trustedCommandUsers` 和 `allowedCommandRules`，并查看服务端 WARN 审计日志。
- 截图工具返回未安装客户端 Mod：目标玩家需要在自己的 NeoForge 客户端 `mods` 目录安装 MineAstr。
- 截图工具返回拒绝或禁用：目标玩家需要在客户端弹窗中同意，或把 `mineastr-client.toml` 的 `screenshotMode` 改为 `"ASK"` / `"AUTO"`。

插件级 `_conf_schema.json` 仅用于展示和发现配置。实际生效的 WebSocket 参数以 AstrBot WebUI 中 `minecraft` 平台适配器的配置为准。
